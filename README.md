# FamilyDNS

A self-hosted parental-control DNS server with a web UI. Block categories of
sites, set per-profile schedules ("no internet after 9pm"), enforce daily and
per-site time limits, log queries, and grant temporary extensions — all on
your own hardware, no third-party DNS provider.

```
                 +------------------+
DNS query ─────▶ |   familydns-dns  | ─▶ category lists, schedules, time limits
   :53           +------------------+         │
                                              ▼
                 +------------------+    +-----------+
HTTP / web ────▶ |  familydns-api   | ─▶ |  Postgres |
   :8080         +------------------+    +-----------+
                          ▲
                          │ static assets
                          │
                       web/dist/
```

## Components

| Module    | What it does                                             | Runtime |
| --------- | -------------------------------------------------------- | ------- |
| `api`     | REST + JWT auth, profiles, devices, time limits          | runnable (Main) |
| `dns`     | UDP DNS server with blocking engine                      | library — needs entrypoint |
| `traffic` | Pcap-based per-device session tracker (for time usage)   | library — needs entrypoint |
| `shared`  | Common models, clock                                     | library |
| `web`     | React + Vite admin UI                                    | static bundle (`web/dist`) |

The `api` server is what runs in production today; the `dns` and `traffic`
modules are wired into the api process or invoked from it. Their tests
exercise their behavior end-to-end against an embedded Postgres.

## Quick start (development, macOS or Linux)

```bash
# Prereqs: JDK 21, Node 22, mill 1.1.5, scalafmt (cs install scalafmt)
git clone git@github.com:sameerparekh/familydns.git
cd familydns
scripts/install-hooks.sh         # set up pre-commit / pre-push hooks

# Run all tests
mill __.test

# Run the API locally (uses an embedded Postgres for tests; for dev you
# need a real Postgres — see config/application.conf.example)
cp config/application.conf.example config/application.conf
# edit secrets in config/application.conf, then:
mill api.run

# Frontend
cd web && npm ci && npm run dev
```

Default admin login: `admin / changeme` — **change this immediately** by
hitting `POST /api/auth/change-password`.

## IDE / BSP

Mill ships a Build Server Protocol (BSP) connector. To set it up:

```bash
mill mill.bsp/install
```

This writes `.bsp/mill-bsp.json`, which Metals (VS Code) and IntelliJ both
auto-detect when they open the repository. The file is gitignored — each
developer runs the command on their own machine because it bakes in an
absolute path to the local `mill` binary.

Sanity check:

- **VS Code + Metals**: open the repo, accept the "Import build" prompt;
  Metals will pick `mill-bsp` over sbt/bloop.
- **IntelliJ**: File → Open → select the repo root → choose **BSP** when
  prompted (not sbt). Subsequent reloads are via the BSP refresh button.

## Deployment (Linux)

The deployment model: a Linux host runs `scripts/deploy.sh` either by hand
or on a timer; it pulls the latest `main`, builds the assembly + frontend,
and restarts a systemd unit. Every artifact (the unit file, the deploy
script, the bootstrap script) lives in this repo, so updating any of them
is a normal PR.

### One-time host bootstrap

On a fresh Linux box (Debian / Ubuntu), as root:

```bash
# Clone just enough to run the bootstrap
git clone git@github.com:sameerparekh/familydns.git /opt/familydns/repo
sudo /opt/familydns/repo/scripts/bootstrap-host.sh
```

`bootstrap-host.sh` is idempotent and:

1. Installs JDK 21, Node 22, Coursier, Mill 1.1.5, scalafmt
2. Creates the `familydns` system user and `/opt/familydns` layout
3. Symlinks `deploy/familydns-api.service` into `/etc/systemd/system`
   (so updates to the unit flow with `git pull`)
4. Writes `/etc/systemd/system/familydns-deploy.service` and a `.timer`
   that runs `scripts/deploy.sh` hourly (also git-tracked logic — the
   timer just invokes the script from the repo)
5. Drops a sample `/etc/familydns/application.conf` from
   `config/application.conf.example`
6. Adds a sudoers rule letting the `familydns` user restart the API and
   write into `/opt/familydns/`

After bootstrap, edit `/etc/familydns/application.conf` (set
`jwt.secret` and `db.password`), then:

```bash
sudo systemctl enable --now familydns-api.service
sudo systemctl enable --now familydns-deploy.timer    # auto-pull every hour
```

### Manual deploy

```bash
sudo -u familydns /opt/familydns/repo/scripts/deploy.sh
# logs:  journalctl -t familydns-deploy
# state: cat /opt/familydns/deploy.log    # rev + timestamp per deploy
```

Environment knobs (set on the command line or in
`/etc/familydns/api.env`):

| Var                   | Default | Effect                                      |
| --------------------- | ------- | ------------------------------------------- |
| `FAMILYDNS_BRANCH`    | `main`  | Branch to track                             |
| `FAMILYDNS_PREFIX`    | `/opt/familydns` | Install root                       |
| `FAMILYDNS_NO_WEB`    | `0`     | Skip frontend build                         |
| `FAMILYDNS_NO_RESTART`| `0`     | Build but don't restart the service         |

### Why the deploy logic lives in the repo

`bootstrap-host.sh` symlinks the systemd units **into the repo checkout**
rather than copying them. That way:

- `git pull` (or the deploy timer) is enough to roll out a new unit file
- the boot script (`bootstrap-host.sh`), the deploy script (`deploy.sh`),
  and the unit file (`deploy/familydns-api.service`) are all reviewed via
  PRs against `main` like any other code
- a deploy timer can re-run `scripts/deploy.sh` from the freshly-pulled
  repo — fixes to the deploy logic apply on the next tick

If you'd rather not symlink, copy the unit and re-copy after each pull —
but you lose the auto-update property.

## Configuration

`config/application.conf` (HOCON) is the source of truth. The example is at
`config/application.conf.example`. Notable keys:

- `familydns.db.*` — Postgres connection
- `familydns.http.port` — API port (default `8080`)
- `familydns.http.staticDir` — where to serve the React bundle from
- `familydns.jwt.secret` — **must be ≥ 32 random chars**, change before going live
- `familydns.jwt.expiryHours` — JWT lifetime (default 24h)
- `familydns.dns.cacheRefreshSeconds` — how often the DNS server reloads
  blocklists / schedules from Postgres

## Testing

Tests use [zonky/embedded-postgres](https://github.com/zonkyio/embedded-postgres),
so no external DB is required.

```bash
mill __.test                  # everything
mill api.test                 # api only
mill dns.test                 # dns only
mill shared.test
mill traffic.test
```

CI (`.github/workflows/ci.yml`) runs:
1. `scalafmt --check`
2. `mill __.compile`
3. `mill __.test`
4. `npm run type-check && npm run lint && npm run build`

## Git hooks

`.githooks/` ships pre-commit and pre-push hooks. Install once per clone:

```bash
scripts/install-hooks.sh
```

This sets `core.hooksPath = .githooks`, so updates flow with the repo.

- **pre-commit (~5s)** — `scalafmt --check` and `eslint` on staged files only
- **pre-push (~5s)** — `scalafmt --check`, `tsc --noEmit`, `eslint` on the
  whole tree

Both can be bypassed in an emergency with `--no-verify`.

## License

See [LICENSE](LICENSE) (TBD).
