#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
ln -sf "$REPO_ROOT/frontend/pre-push.sh" "$REPO_ROOT/.git/hooks/pre-push"
echo "✓ Git pre-push hook installed"
