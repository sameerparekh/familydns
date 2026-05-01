#!/usr/bin/env bash
# Live end-to-end smoke tests against a running staging stack.
#
#   docker compose -f docker/docker-compose.yml up -d --build
#   scripts/e2e-tests.sh
#
# Exits non-zero on the first failure. The GitHub Actions e2e workflow runs
# this exact script — green here is the gate for promoting main → production.
#
# Env:
#   E2E_BASE_URL  default http://127.0.0.1:8080
set -euo pipefail

BASE="${E2E_BASE_URL:-http://127.0.0.1:8080}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

pass() { echo "  ✓ $*"; }
fail() { echo "  ✗ $*" >&2; exit 1; }
step() { echo; echo "▶ $*"; }

# Quick wait for the API to come up (compose healthcheck should already gate, but be safe).
step "Waiting for API at $BASE"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H 'content-type: application/json' -d '{}' \
    "$BASE/api/auth/login" 2>/dev/null || true)
  if [ "$code" = 400 ] || [ "$code" = 401 ]; then pass "API responding ($code)"; break; fi
  if [ "$i" = 60 ]; then fail "API never came up (last code: $code)"; fi
  sleep 1
done

step "Login as admin/changeme"
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'content-type: application/json' \
  -d '{"username":"admin","password":"changeme"}')
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "no token in login response: $LOGIN"
echo "$LOGIN" | grep -q '"role":"admin"' || fail "admin role missing"
pass "logged in"

AUTH=(-H "authorization: Bearer $TOKEN")

step "Profiles list (auth required)"
UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/profiles")
[ "$UNAUTH" = "401" ] || fail "expected 401 without auth, got $UNAUTH"
pass "401 without auth"

curl -fsS "${AUTH[@]}" "$BASE/api/profiles" >"$TMP/profiles.json"
pass "profiles list returned $(wc -c <"$TMP/profiles.json") bytes"

step "Create a profile"
CREATE=$(curl -fsS -X POST "$BASE/api/profiles" \
  "${AUTH[@]}" -H 'content-type: application/json' \
  -d '{"name":"e2e-test","blockedCategories":["adult"],"extraBlocked":[],"extraAllowed":[],"paused":false,"schedules":[],"timeLimit":null,"siteTimeLimits":[]}')
PID=$(echo "$CREATE" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$PID" ] || fail "no profile id in create response: $CREATE"
pass "created profile id=$PID"

step "Fetch the profile we just created"
curl -fsS "${AUTH[@]}" "$BASE/api/profiles/$PID" | grep -q '"name":"e2e-test"' \
  || fail "profile $PID did not round-trip"
pass "profile round-trips"

step "Devices endpoint"
curl -fsS "${AUTH[@]}" "$BASE/api/devices" >/dev/null
pass "devices endpoint OK"

step "Logs + stats endpoints"
curl -fsS "${AUTH[@]}" "$BASE/api/logs" >/dev/null
curl -fsS "${AUTH[@]}" "$BASE/api/stats" >/dev/null
pass "logs + stats OK"

step "Blocklists endpoint"
curl -fsS "${AUTH[@]}" "$BASE/api/blocklists" >/dev/null
pass "blocklists OK"

step "DNS server responds on UDP :5353"
DNS_HOST="${E2E_DNS_HOST:-127.0.0.1}"
DNS_PORT="${E2E_DNS_PORT:-5353}"
if ! command -v dig >/dev/null 2>&1; then
  fail "dig not installed — install dnsutils/bind-tools"
fi
# Wait for DNS server to bind (may start a moment after the api).
for i in $(seq 1 30); do
  if dig +tries=1 +time=2 @"$DNS_HOST" -p "$DNS_PORT" example.com >/dev/null 2>&1; then
    pass "dns server reachable on $DNS_HOST:$DNS_PORT"
    break
  fi
  if [ "$i" = 30 ]; then fail "dns server never came up on $DNS_HOST:$DNS_PORT"; fi
  sleep 1
done

# Unknown client (no profile/device row) should still get an answer — it falls
# through to the default Adults profile and forwards upstream. We just need a
# well-formed response (status NOERROR or SERVFAIL is fine — the point is the
# server is parsing queries and writing replies).
DIG_OUT=$(dig +tries=1 +time=3 @"$DNS_HOST" -p "$DNS_PORT" example.com || true)
echo "$DIG_OUT" | grep -qE 'status: (NOERROR|NXDOMAIN|SERVFAIL|REFUSED)' \
  || fail "dns server did not return a parseable response: $DIG_OUT"
pass "dns server answered example.com"

step "Traffic monitor running + /api/time/status endpoint"
# The traffic container captures pcap on its own bridge eth0, which only sees
# its own packets — enough to verify the service starts and the pcap4j JNA
# binding works. Real per-device capture happens on the host via systemd.
if command -v docker >/dev/null 2>&1; then
  COMPOSE_FILE="${COMPOSE_FILE:-docker/docker-compose.yml}"
  if [ -f "$COMPOSE_FILE" ]; then
    for i in $(seq 1 30); do
      state=$(docker compose -f "$COMPOSE_FILE" ps --format '{{.Service}} {{.State}}' 2>/dev/null \
        | awk '$1=="traffic"{print $2}')
      if [ "$state" = "running" ]; then pass "traffic container running"; break; fi
      if [ "$i" = 30 ]; then fail "traffic container never reached running state (last: $state)"; fi
      sleep 1
    done
    if docker compose -f "$COMPOSE_FILE" logs traffic 2>/dev/null \
        | grep -q "FamilyDNS traffic monitor starting"; then
      pass "traffic monitor logged startup"
    else
      fail "traffic monitor did not log expected startup line"
    fi
  fi
fi

# Synthetic queries to drive a tiny bit of traffic past the dns server (the
# traffic container can't see this on its own veth, but if pcap is broken or
# the upstream resolver is wedged the dns server itself will fail to answer).
for d in example.com wikipedia.org github.com; do
  dig +tries=1 +time=2 @"$DNS_HOST" -p "$DNS_PORT" "$d" >/dev/null 2>&1 || true
done

# Confirm /api/time/status returns a JSON array. Usage rows only appear when
# the traffic container actually captures matching packets, which the bridge
# topology can't guarantee — so we don't gate on row content here.
STATUS=$(curl -fsS "${AUTH[@]}" "$BASE/api/time/status")
echo "$STATUS" | grep -q '^\[' || fail "time/status did not return a JSON array: $STATUS"
pass "/api/time/status responded"

echo
echo "All e2e checks passed."
