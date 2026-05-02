#!/usr/bin/env bash
# Reject modifications, deletions, or renames of existing Flyway migration
# files. New migrations (added files) are allowed. Editing an applied
# migration causes a checksum mismatch on production startup (see #60).
# Enforced by CI on PRs targeting main (see #61).
set -euo pipefail

BASE="${1:?usage: check-migrations.sh <base-ref>}"
DIR="api/resources/db/migration"

changes="$(git diff --name-status "${BASE}"...HEAD -- "${DIR}")"

if [[ -z "${changes}" ]]; then
  echo "No migration changes."
  exit 0
fi

bad=0
while IFS= read -r line; do
  [[ -z "${line}" ]] && continue
  status="$(printf '%s' "${line}" | cut -f1)"
  files="$(printf '%s' "${line}" | cut -f2-)"
  case "${status}" in
    A)
      echo "OK (added):    ${files}"
      ;;
    *)
      echo "BLOCKED (${status}): ${files}" >&2
      bad=1
      ;;
  esac
done <<< "${changes}"

if [[ ${bad} -ne 0 ]]; then
  cat >&2 <<'MSG'

Migration files in api/resources/db/migration must not be modified, deleted,
or renamed once merged to main. Editing an applied migration causes a Flyway
checksum mismatch on production startup (see issue #60). Add a new
V{n+1}__... migration instead.

This rule is enforced by .github/scripts/check-migrations.sh (issue #61).
MSG
  exit 1
fi
