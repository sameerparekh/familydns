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

step "DNS server responds on UDP :5354"
DNS_HOST="${E2E_DNS_HOST:-127.0.0.1}"
DNS_PORT="${E2E_DNS_PORT:-5354}"
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

echo
echo "All e2e checks passed."
