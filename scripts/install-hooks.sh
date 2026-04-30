#!/usr/bin/env bash
# Point this clone's git at the tracked .githooks/ directory.
# Run once per clone (or after a fresh checkout): scripts/install-hooks.sh
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
chmod +x .githooks/pre-commit .githooks/pre-push
git config core.hooksPath .githooks
echo "Hooks installed (core.hooksPath=.githooks)"
