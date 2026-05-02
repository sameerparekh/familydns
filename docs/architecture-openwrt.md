# OpenWRT-based enforcement architecture

Status: **proposed / spec only.** No code change accompanies this document; it
captures the contract that subsequent PRs (issues #67 – #73) will implement.

## 1. Why this exists

The original architecture ran a DNS server and a pcap-based traffic monitor on
the same Linux host as the API. That works only if the host sees every DNS
query and every flow — which it doesn't on a normal home LAN. Clients can:

- hard-code an upstream resolver (`1.1.1.1`, `8.8.8.8`) and bypass the local
  DNS server entirely;
- use DNS-over-HTTPS / DNS-over-TLS, which looks like ordinary HTTPS to the
  pcap monitor;
- talk peer-to-peer or to services that don't show up on the host's NIC.

Per-profile time accounting and per-device blocking are unenforceable from a
host that isn't on the data path. Moving enforcement to the **gateway** — an
OpenWRT router every device must traverse to reach the internet — fixes this.

## 2. Topology

```
                                                 +----------------------+
LAN devices ───┐                                 |  api server (Scala)  |
  (phones,     │   all egress traffic            |  ┌────────────────┐  |
   laptops,    │  ───────────────▶               |  │  Postgres      │  |
   TVs)        │                                 |  └────────────────┘  |
               ▼                                 |   • policy API       |
        +-------------------+   HTTP             |   • usage ingest API |
        |  OpenWRT router   |◀──bearer token────▶|   • block-page UI    |
        |  • dnsmasq + RPZ  |                    |   • web admin UI     |
        |  • nftables/ipset |                    +----------------------+
        |  • familydns-     |
        |    agent (lua)    |
        +-------------------+
```

- **API server** is a single instance. All endpoints (admin, web, router) live
  under one HTTP port, distinguished by path prefix.
- **HTTP, not HTTPS**, for the router endpoints in the initial rollout. Switch
  to HTTPS / mTLS once the API server moves into the cloud.
- **Authentication** between router and API is a per-router bearer token
  obtained via a one-shot enrollment flow (§5.1).

## 3. Component responsibilities

### OpenWRT router (gateway)

- DHCP + DNS for the LAN (dnsmasq, default OpenWRT setup).
- Enforces filtering: drops or DNATs blocked flows.
- Accounts traffic per `(mac, hostname)` using nftables counters and the
  dnsmasq query log (or the dnsmasq `--ipset=` directive, see §6).
- Periodically pulls policy and pushes usage to the API.
- Serves a local block page that 302s to the API's `/blocked` URL for any
  blocked HTTP request.

### API server (this repo, `api/` module)

- Stores profiles, schedules, time limits, blocklists, devices, users,
  routers, traffic reports, block events.
- Exposes admin/web endpoints (existing) **and** router endpoints (new, §5).
- Renders the public `/blocked` page.
- Owns the policy decision logic (`PolicyEngine`, derived from the existing
  `BlockingEngine` once the `dns/` module is deleted).

### Components being deleted

- `dns/` — replaced by dnsmasq on the router.
- `traffic/` — replaced by the OpenWRT agent's nftables-based accounting.

## 4. Decision model: policy snapshot, not per-flow round-trips

The router does **not** round-trip to the API on every connection. Instead:

1. Every ~60 s the agent pulls a **policy snapshot** containing everything
   needed to make decisions locally: device→profile assignments, blocked
   categories, schedules, time limits, `time_used_today`, paused state.
2. The agent renders that snapshot into dnsmasq + nftables config and
   reloads them atomically.
3. Per-flow decisions happen in-kernel (nftables) or in dnsmasq — no API
   call per request.
4. `POST /api/router/decision` exists as a **fallback** for hostnames not in
   the snapshot, and is optional for v1.

Worst-case staleness from this model is one poll interval (~60 s). That is
acceptable for parental-control use cases; an instant-block requirement can
be met later by a server-push channel (websocket, SSE) if needed.

## 5. Router HTTP API

All endpoints below are served by the existing API process under
`/api/router/*`. Bearer-token auth on every request except `/register`
(which uses a one-time enrollment token) and the public `/blocked` page.

### 5.1 `POST /api/router/register`

Boot-time enrollment. The user creates a router record in the admin UI;
the API returns a one-time enrollment token they paste into the OpenWRT
package's UCI config. The agent calls this endpoint once to exchange the
enrollment token for a long-lived router token.

**Request**

```json
{
  "enrollment_token": "et_5f3c9b...",
  "router_name": "home-gw",
  "openwrt_version": "23.05.3",
  "agent_version": "0.1.0"
}
```

**Response 200**

```json
{
  "router_id": "9c1f2e8a-...",
  "router_token": "rt_a7d12b..."
}
```

**Errors**: `401` on invalid/used enrollment token, `409` on duplicate name.

The enrollment token is single-use; the API marks it consumed on success.

### 5.2 `GET /api/router/policy?since=<etag>`

Polled every ~60 s. Returns the full enforcement snapshot, or `304 Not
Modified` if the client's ETag still matches.

**Response 200**

```json
{
  "etag": "sha256:abc123...",
  "generated_at": "2026-05-02T14:00:00Z",
  "default_profile_id": 1,
  "devices": [
    { "mac": "aa:bb:cc:11:22:33", "profile_id": 3, "name": "kid-ipad" }
  ],
  "profiles": [
    {
      "id": 3,
      "name": "kids",
      "paused": false,
      "blocked_categories": ["ads", "adult"],
      "extra_blocked": ["tiktok.com"],
      "extra_allowed": ["khanacademy.org"],
      "schedules": [
        { "days": ["MON","TUE","WED","THU","FRI"],
          "block_from": "21:00", "block_until": "07:00" }
      ],
      "daily_minutes": 120,
      "site_limits": [
        { "domain": "youtube.com", "minutes": 30, "label": "YouTube" }
      ],
      "time_used_today": {
        "total_minutes": 47,
        "by_domain": { "youtube.com": 12 }
      },
      "extensions_today_minutes": 15
    }
  ],
  "blocklists": {
    "ads":   { "version": "2026-04-29", "url": "/api/blocklists/ads.rpz" },
    "adult": { "version": "2026-04-29", "url": "/api/blocklists/adult.rpz" }
  }
}
```

**Response 304** when `If-None-Match: <etag>` (or `?since=<etag>`) matches the
current snapshot. The body is empty.

ETag is computed deterministically over the snapshot's content, so the same
logical state always produces the same ETag.

`time_used_today.total_minutes` excludes domains covered by `site_limits` —
the router enforces both limits independently, mirroring `BlockingEngine`.

### 5.3 `GET /api/blocklists/<category>.rpz`

Returns an RPZ-formatted (or dnsmasq-`address=`-formatted; TBD during
implementation, RPZ preferred) blocklist for the named category. Versioned
and ETagged. The agent caches by `version` from the policy snapshot and only
refetches when the version changes.

### 5.4 `POST /api/router/usage`

Sent every 5 minutes. Idempotent on `(router_id, period_start, mac, hostname)`
so retries are safe.

**Request**

```json
{
  "router_id": "9c1f2e8a-...",
  "period_start": "2026-05-02T14:00:00Z",
  "period_end":   "2026-05-02T14:05:00Z",
  "records": [
    {
      "mac": "aa:bb:cc:11:22:33",
      "ip":  "192.168.1.42",
      "hostname": "youtube.com",
      "active_seconds": 240,
      "bytes_in":  38123412,
      "bytes_out": 921000
    }
  ]
}
```

**Response 200**: empty body.

Server actions on receipt:
1. Insert each record into `traffic_reports` (audit log, raw values).
2. Increment `time_usage(device_mac, domain, date, minutes_used, bytes_in, bytes_out)`
   — `active_seconds` rounds up to whole minutes and adds to `minutes_used`;
   bytes accumulate.
3. Update `devices.last_seen_ip = ip`, `devices.last_seen_at = period_end`
   for each MAC seen in the batch.

`hostname` is the forward-lookup hostname dnsmasq resolved for the client —
not a reverse-DNS lookup of the destination IP, which is unreliable for CDNs.
See §6 for how the router determines this.

### 5.5 `POST /api/router/events`

Out-of-band events (DHCP leases, first-seen MACs, dnsmasq query log lines).
Used to populate the unknown-device list (existing issue #19) and feed device
autodetection (#18). Best-effort — the API may store them in `query_logs` or
a dedicated table; exact storage TBD in #69.

**Request**

```json
{
  "router_id": "9c1f2e8a-...",
  "events": [
    { "type": "dhcp_lease",
      "mac": "aa:bb:cc:11:22:33", "ip": "192.168.1.42",
      "hostname": "kid-ipad", "ts": "2026-05-02T14:01:13Z" },
    { "type": "dns_query",
      "mac": "aa:bb:cc:11:22:33",
      "qname": "youtube.com", "qtype": "A",
      "blocked": false, "ts": "2026-05-02T14:01:14Z" }
  ]
}
```

**Response 200**: empty body.

### 5.6 `POST /api/router/decision`  *(optional fallback)*

For hostnames not in the most recent snapshot. Not required in v1.

**Request**: `{ "mac": "aa:bb:...", "hostname": "..." }`

**Response 200**: `{ "allow": false, "reason": "category:adult", "expires_at": "..." }`

Each call also writes a row to `block_events` (when `allow=false`) for the
"recently blocked" UI.

### 5.7 `GET /blocked` *(public, no auth)*

Query params: `mac`, `host`, `reason`. Renders a React route that shows the
user *why* they were blocked (paused, schedule until 07:00, daily limit hit,
category, `extra_blocked`) and offers a "request extension" button gated on
parent login. This is the URL the router's local block-page redirects to.

## 6. OpenWRT agent design (`openwrt/` package)

This section is implementation guidance, not part of the wire contract. It
exists here so the API and the agent stay in sync.

### 6.1 Capabilities OpenWRT provides that we rely on

- **Per-MAC routing via nftables.** `ether saddr @profile3_macs` matches
  packets from any MAC in the named set. We maintain one set per profile.
- **Per-domain IP sets via dnsmasq's `--ipset=` directive.** dnsmasq
  populates a named nftables/ipset set with whatever IPs a hostname
  resolves to. nftables can then match destination IP against that set
  to make per-domain decisions on L3 traffic.
- **Per-MAC dnsmasq tagging via `dhcp-host=...,set:profileN`.** Different
  `address=` / `server=` rules can apply per MAC tag. So `tiktok.com`
  returns NXDOMAIN for kids' phones and resolves normally for parents'.
- **nftables counter objects** keyed on `ether saddr . ip daddr` give
  per-MAC, per-IP byte counts. Scrape with `nft -j list counters table inet familydns`.
- **dnsmasq query log** (`--log-queries=extra`) maps `(mac, time) → hostname`
  so we can attribute byte counts to the hostname the client actually
  looked up.
- **uhttpd** (already on OpenWRT) can serve a static block page on a
  loopback port; nftables `dnat` redirects blocked HTTP/80 to it.

### 6.2 Why forward-lookup hostnames, not reverse DNS

Reverse DNS of a destination IP often returns generic CDN PTRs
(`lb-13.akamai.net`, `ec2-...amazonaws.com`) that have no relationship to
the hostname the user thought they were visiting. dnsmasq's `--ipset=`
directive populates the nftables set *at lookup time*, so we know — for
each IP in the set — which hostname the user resolved to get there.
That's the correct attribution for time-accounting.

For HTTPS connections that bypass dnsmasq (rare, but possible with apps
that hard-code IPs), the destination IP won't be in any of our domain
sets and we attribute the bytes to a generic `unknown` hostname bucket.

### 6.3 Package layout

```
openwrt/
├── Makefile                            # opkg metadata, builds via OpenWRT SDK
├── files/
│   ├── etc/init.d/familydns            # procd init script
│   ├── etc/config/familydns            # UCI: api_url, router_token, poll_interval
│   ├── usr/sbin/familydns-agent        # main daemon (Lua)
│   ├── usr/lib/familydns/policy.lua    # snapshot fetcher, atomic apply
│   ├── usr/lib/familydns/usage.lua     # nftables counter scraper, reporter
│   ├── usr/lib/familydns/render.lua    # writes dnsmasq + nft fragments
│   └── www/familydns/block.html        # local block page → 302 to api /blocked
└── README.md                            # build, flash, enroll
```

### 6.4 Daemon loop (single process, three timers)

- **Policy timer (60 s)** —
  `GET /api/router/policy?since=<etag>` with `If-None-Match`.
  On `200`: atomically rewrite `/tmp/dnsmasq.d/familydns.conf` and
  `/tmp/nftables.d/familydns.nft` from `render.lua`, then
  `/etc/init.d/dnsmasq reload && nft -f /tmp/nftables.d/familydns.nft`.
  Reload is fast (<200 ms) and doesn't drop established TCP flows.
  On `304`: do nothing.
- **Usage timer (5 min)** —
  scrape nftables counters, correlate with dnsmasq query log + DHCP
  leases, build the report, `POST /api/router/usage`. On 200, reset
  counters. On failure, retain counters and retry next tick — the
  endpoint is idempotent.
- **Event watcher** —
  dnsmasq `--dhcp-script` hook for DHCP events; cheap log tail for
  query events; both POSTed to `/api/router/events` in small batches.

### 6.5 Time-limit enforcement

The policy snapshot includes `time_used_today.total_minutes` and the
profile's `daily_minutes`. The agent computes `remaining = limit - used +
extensions` and, when `remaining <= 0`, swaps in an nftables rule that
drops/redirects all egress for that profile's MAC set until the next
poll (which will refresh the value or, after midnight, reset it).

Worst case: a kid gets ~60 s of bonus time after the limit is hit. Fine.

### 6.6 Block-page redirect

```
nft 'add rule inet familydns block_redirect tcp dport 80 dnat to 127.0.0.1:8081'
```

uhttpd on `127.0.0.1:8081` serves `/www/familydns/block.html`, which is a
3-line page that 302s to `http://<api>/blocked?mac=<mac>&host=<host>&reason=<r>`
(values filled in by a tiny CGI script that reads the original
destination from conntrack).

HTTPS to a blocked host can't be intercepted cleanly without installing a
CA on every device, so blocked HTTPS just times out. This matches the
behavior of every commercial parental-control box and is acceptable.

## 7. Schema impact (preview, lands in #67)

New tables:
- `routers` — registered gateways and their tokens.
- `traffic_reports` — raw audit log of usage POSTs.
- `block_events` — record of decisions returned by `/decision` and
  redirects served by `/blocked`.

New columns:
- `time_usage.bytes_in bigint default 0`
- `time_usage.bytes_out bigint default 0`

No drops in v1. The `query_logs` table stays — it can be fed by
`/api/router/events` for dnsmasq queries, replacing the deleted DNS server
as the source.

## 8. Rollout sequence (issues)

1. **#66** — this document.
2. **#67** — `V2__openwrt.sql` migration + repos.
3. **#68** — `GET /api/router/policy` + `/api/blocklists/<cat>.rpz` + enrollment + admin UI.
4. **#69** — `POST /api/router/usage` + `POST /api/router/events`.
5. **#70** — `POST /api/router/decision` + public `/blocked` page.
6. **#71** — delete `dns/` and `traffic/` modules, units, bootstrap refs.
7. **#72** — `openwrt/` package: agent, dnsmasq+nftables config, opkg.
8. **#73** — e2e fake-router in staging compose.

Steps 3–5 land before 6: we don't delete the old enforcement until the new
one is at least addressable on the API side.

## 9. Open questions deferred to implementation

- RPZ vs dnsmasq `address=` format for the blocklist endpoint — pick during #68.
- Exact storage shape for `/api/router/events` — extend `query_logs`, or new
  table? Decided in #69.
- Active-seconds computation on the router — start with "any 5-minute bucket
  with bytes > threshold counts as 5 min". Refine in #72 if too coarse.
- Whether `/api/router/decision` ships in v1 or is deferred. Currently
  scheduled for #70 but optional for the agent.
