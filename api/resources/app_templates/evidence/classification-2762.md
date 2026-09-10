# App-catalog classification — #2762 (2026-09-10)

Operator request, not a discovery sweep: "create apps for amazon and sportys."
Both brands were named up front, so Step 0 was used to *confirm and scope* them
rather than to find candidates.

Source: prod cloud API, `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500`,
pulled for all 11 non-IoT devices on 2026-09-10. Read-only.

## Per-apex traffic (30d)

| device | apex | bytes | hits |
| --- | --- | ---: | ---: |
| Kid Laptop | `sportys.com` | 2,035,751,680 | 365 |
| Sameer Mac | `sportys.com` | 23,156,211 | 19 |
| Sameer iPhone | `sportys.com` | 7,831,757 | 5 |
| Kid Laptop | `ssl-images-amazon.com` | 3,085,261,502 | 422 |
| Prima iPad | `ssl-images-amazon.com` | 2,768,653,897 | 155 |
| Octavius iPad | `ssl-images-amazon.com` | 1,442,495,984 | 172 |
| Quintus iPad | `ssl-images-amazon.com` | 422,031,602 | 27 |
| Sameer iPhone | `ssl-images-amazon.com` | 390,694,223 | 222 |
| Rachel Mac | `ssl-images-amazon.com` | 309,592,584 | 138 |
| Rachel iPhone | `ssl-images-amazon.com` | 225,791,210 | 133 |
| Sameer Mac | `ssl-images-amazon.com` | 113,142,784 | 24 |
| Kid Laptop | `amazon.com` | 694,476,092 | 3820 |
| Sameer iPhone | `amazon.com` | 344,569,682 | 1087 |
| Prima iPad | `amazon.com` | 323,040,666 | 570 |
| Octavius iPad | `amazon.com` | 295,949,296 | 664 |
| Rachel Mac | `amazon.com` | 252,947,918 | 2832 |
| Rachel iPhone | `amazon.com` | 144,512,811 | 393 |
| Quintus iPad | `amazon.com` | 54,118,961 | 88 |
| Sameer Mac | `amazon.com` | 48,384,619 | 466 |
| Sameer iPhone | `media-amazon.com` | 199,841,358 | 102 |
| Octavius iPad | `media-amazon.com` | 128,801,170 | 8 |
| Rachel iPhone | `media-amazon.com` | 83,930,797 | 65 |
| Kid Laptop | `media-amazon.com` | 65,060,914 | 26 |
| Quintus iPad | `media-amazon.com` | 63,602,537 | 1 |
| Rachel Mac | `media-amazon.com` | 57,424,070 | 38 |
| Prima iPad | `media-amazon.com` | 33,606,057 | 4 |
| Sameer Mac | `media-amazon.com` | 1,702,793 | 2 |

`recent-apexes` returns bytes/hits per APEX and a flat `subdomains[]` list, with
no per-subdomain byte split. So the `amazon.com` row above is the whole-company
zone, not the storefront — which is exactly the reason the template excludes it.

## Gap check (Step 1)

Neither apex is covered by any existing template. `grep -rniE 'amazon|sportys'`
over `api/resources/app_templates/*.yml` returns only two incidental matches in
`arduino.yml` and `connectivity-test.yml` comments (both about `amazonaws.com`
hostnames, not Amazon-the-app). The Amazon domains already in the catalog are
ad-tech entries in `blocklists/ads.yml`: `amazon-adsystem.com`,
`amazon-ads-attestation.com`, `paa-reporting-advertising.amazon`.

Both are genuine gaps. Both classified as new app templates (Step 2).

## Observed subdomains, per device

### `sportys.com`

| device | subdomains |
| --- | --- |
| Kid Laptop | `dl.videos`, `stream.videos`, `ye.courses` |
| Sameer iPhone | `dl.videos`, `stream.videos`, `ye.courses` |
| Sameer Mac | `dl.videos`, `stream.videos`, `ye.courses`, `www`, `pspdfkit.courses` |

The 2.04 GB on Kid Laptop is course video from Sporty's Online Training and
nothing else — the store host `www` never appears on that device.

### `amazon.com`

`www.amazon.com` is the only subdomain present on all eight devices that hit the
apex. Everything else observed under it is a different Amazon product, telemetry,
or ad surface:

| subdomain | what it is | seen on |
| --- | --- | --- |
| `aws.amazon.com`, `d2c.aws.amazon.com`, `vs.aws.amazon.com` | AWS console / docs | Sameer iPhone |
| `api.amazon.com` | Login with Amazon (shared vendor API), Alexa | Rachel iPhone, Sameer iPhone |
| `arcus-uswest.amazon.com`, `msh.amazon.com` | Alexa / connected-device infra | Rachel iPhone, Sameer iPhone, Octavius iPad |
| `read.amazon.com`, `music.amazon.com` | Kindle, Amazon Music | Rachel Mac |
| `watch.amazon.com`, `prime.amazon.com`, `atv-ps.amazon.com` | Prime Video | Sameer Mac, Sameer iPhone, Rachel iPhone |
| `apay-us.amazon.com` | Amazon Pay | Kid Laptop |
| `pharmacy.amazon.com` | Amazon Pharmacy | Rachel iPhone |
| `fls-na`, `unagi`, `unagi-na`, `ipv6.unagi-na`, `data`, `transient` | telemetry | most devices |
| `sponsored-ads`, `aax-us-east-retail-direct`, `affiliate-program` | Amazon Ads | most devices |

### `ssl-images-amazon.com` / `media-amazon.com`

`images-na`, `images-eu` / `m`, `c`, `metrics`.

## Host-set decisions (Step 3)

### `sportys` — `www.sportys.com`, `videos.sportys.com`, `courses.sportys.com`

Three subdomain entries suffix-match all five observed hosts via
`HostMatch.matchesApex`. Resolution as of 2026-09-10:

| host | resolves to |
| --- | --- |
| `www.sportys.com` | CNAME `sportys.com` → 199.232.66.132 (Fastly) |
| `stream.videos.sportys.com` | 99.84.105.{16,38,60,119} (CloudFront) |
| `dl.videos.sportys.com` | 18.238.176.{58,71,74,112} (CloudFront) |
| `courses.sportys.com` | 16.59.107.150, 3.151.66.199 |
| `ye.courses.sportys.com` | CNAME `prod-ye-training.simplysporty.net` → 16.59.159.202, 3.23.25.151 |
| `pspdfkit.courses.sportys.com` | CNAME `prod-pspdfkit.simplysporty.net` → 77.112.172.80, 77.112.39.76 (whois `AMAZO-4`) |

Every one of those is a multi-tenant CDN edge, not a dedicated origin.
199.232.66.132 is `SKYCA-3` / Fastly, and Fastly anycast addresses are shared
across customers by design — nothing establishes it as Sporty's alone.

Class-2 overlap check, which `_README.yml` asks for rather than assuming:
`stream.videos.sportys.com` (99.84.105.{16,38,60,119}) and `aws.amazon.com`
(99.84.105.{36,65,73,118}) sit in the SAME CloudFront /24 — and `amazon.yml`
excludes the bare `amazon.com` apex precisely so the AWS console is never
collaterally dropped. The addresses are distinct today and only resolved IPs
enter an `eb_` set, so blocking Sporty's drops no AWS-console address. Recorded
as the host to re-check first if #1663 revisits the Class-2 set.

Bare `sportys.com` excluded. `images.sportys.com` CNAMEs off Sporty's own
infrastructure — `media.esp1.co` → `media.espssl.com` →
`media.espssl.com.cdn.cloudflare.net` → 172.64.144.42 / 104.18.43.214, a
broadly-shared Cloudflare pool. Since `render.lua` emits `nftset=/<host>/`
verbatim and dnsmasq matches by pure suffix, an apex entry would sweep that pool
into the `eb_` drop set. It has also never appeared in traffic, so the
"only template what you've seen used" rule excludes it independently.

Sibling Sportsman's Market brands linked from the store — `sportysacademy.com`,
`flighttrainingcentral.com`, `ipadpilotnews.com`, `airfactsjournal.com`,
`sportystoolshop.com`, `aviationgifts.com`, `preferredliving.com` — are all on
separate infrastructure with no observed traffic. Excluded per the #2596
`renaissance.com` precedent: a topically adjacent sibling apex needs its own
evidence.

### `amazon` — `www.amazon.com`, `ssl-images-amazon.com`, `m.media-amazon.com`, `c.media-amazon.com`

Scoped to the shopping surface. Bare `amazon.com` excluded: the observed-subdomain
table above is the argument, and every entry in it is real traffic from this
household, not a hypothetical. Blocking or time-limiting a kid's Amazon shopping
must not take out the AWS console, Login with Amazon, Alexa, Kindle or Amazon Pay.

`ssl-images-amazon.com` IS listed as an apex, deliberately in contrast: it is a
single-purpose static-image zone, so every child is storefront imagery by
construction. Both observed children CNAME to `m.media-amazon.com` →
`c.media-amazon.com`, so the apex entry adds no IP exposure the template does not
already accept.

`media-amazon.com` is NOT listed as an apex, because `metrics.media-amazon.com`
is telemetry and this template carries only hosts whose bytes are storefront
imagery. Same call as excluding `telemetry.canva.com` and `sgtm.arduino.cc`.
`metrics.` was observed CNAMEing to `ecp.map.fastly.net` → 199.232.65.51, but
that is NOT the basis for the exclusion and must not be restated as "a pool no
kept host touches": the kept image hosts are DNS-steered across CDNs, Fastly
included, so pool-disjointness here is unverified.

`c.media-amazon.com` is kept because it is observed as a directly-queried name,
not merely as a CNAME target — it needs its own entry to be attributed at all.
It is NOT justified as "adds no incremental IPs": that holds only in the
steering state where `m.` resolves through `c.`.

Amazon steers the image hosts between CDNs by DNS: `m.media-amazon.com` answered
on Akamai (23.215.223.x via `a.media-amazon.com.akamaized.net`) and, minutes
later, on CloudFront (13.226.249.165 / 99.84.98.145) via `c.media-amazon.com`.
Any single `dig` of these hosts is a sample, not the host's IP set. Accepted
Class-2 latent risk per `_README.yml` — that IS where the app's bytes live.

Prime Video left out as a distinct service; netflix/youtube/twitch are separate
apps and Prime Video should be too if it ever needs a template.

Residual: `media-amazon.com` also serves IMDb and Prime Video imagery, so a future
Prime Video app would find some of its image bytes already attributed to `amazon`.

## Validation (Step 5)

`mill api.test.testOnly 'wifihaven.api.feature.AppTemplatesSpec'` — 38 tests
passed, 0 failed. Seeder log confirms `slug=amazon (id=44, hosts=4)` and
`slug=sportys (id=45, hosts=3)`. `scalafmt --check --non-interactive` clean.
No blocklist files touched, so `BundledBlocklistsSpec` was not run.
