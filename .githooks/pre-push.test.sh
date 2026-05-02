#!/usr/bin/env bash
# Tests the migrations-immutability section of .githooks/pre-push.
# Sets up a tmp repo with a remote so origin/main resolves, then invokes
# the same logic the hook runs. Does NOT exercise scalafmt/tsc/eslint.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="${REPO_ROOT}/.github/scripts/check-migrations.sh"
[[ -x "${SCRIPT}" ]] || chmod +x "${SCRIPT}"

setup() {
  tmp="$(mktemp -d)"
  origin="${tmp}/origin.git"
  work="${tmp}/work"
  git init -q --bare "${origin}"
  git init -q "${work}"
  cd "${work}"
  git config user.email t@t.io
  git config user.name tester
  git config commit.gpgsign false
  git remote add origin "${origin}"
  mkdir -p api/resources/db/migration .github/scripts
  cp "${SCRIPT}" .github/scripts/check-migrations.sh
  echo "-- v1" > api/resources/db/migration/V1__init.sql
  git add . && git commit -q -m base
  git branch -M main
  git push -q origin main
  git fetch -q origin
}

cleanup() { rm -rf "${tmp}"; }

# Mirrors the hook's migration section. Returns 0 = pass, 1 = block, 2 = skipped.
run_hook_section() {
  local fail=0
  local current_branch
  current_branch="$(git symbolic-ref --short HEAD 2>/dev/null || echo "")"
  if [[ "${current_branch}" == "main" ]]; then
    return 0
  elif ! git rev-parse --verify --quiet origin/main >/dev/null; then
    echo "skipped: origin/main not fetched" >&2
    return 2
  else
    if ! .github/scripts/check-migrations.sh origin/main; then
      fail=1
    fi
  fi
  return ${fail}
}

run_case() {
  local name="$1"; shift
  local want_rc="$1"; shift
  setup
  "$@"
  set +e
  run_hook_section >/tmp/hook.out 2>/tmp/hook.err
  local rc=$?
  set -e
  cleanup
  if [[ "${rc}" -eq "${want_rc}" ]]; then
    echo "PASS: ${name}"
  else
    echo "FAIL: ${name} (rc=${rc}, wanted ${want_rc})"
    echo "--- stdout ---"; cat /tmp/hook.out
    echo "--- stderr ---"; cat /tmp/hook.err
    exit 1
  fi
}

case_branch_modifies_v1() {
  git checkout -q -b feature
  echo "-- changed" >> api/resources/db/migration/V1__init.sql
  git add . && git commit -q -m mod
}

case_branch_adds_v2() {
  git checkout -q -b feature
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  git add . && git commit -q -m add
}

case_on_main() {
  echo "-- v2" > api/resources/db/migration/V2__add.sql
  git add . && git commit -q -m "on main"
}

case_origin_main_missing() {
  git checkout -q -b feature
  git remote remove origin
  echo "-- changed" >> api/resources/db/migration/V1__init.sql
  git add . && git commit -q -m mod
  # Drop the remote-tracking ref too
  git update-ref -d refs/remotes/origin/main 2>/dev/null || true
}

run_case "branch modifies V1 → blocked"        1 case_branch_modifies_v1
run_case "branch only adds V2 → passes"        0 case_branch_adds_v2
run_case "on main → no-op"                     0 case_on_main
run_case "origin/main unfetched → skip"        2 case_origin_main_missing

echo "All pre-push hook tests passed."
