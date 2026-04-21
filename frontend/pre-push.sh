#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# Collect files that differ between local and remote for the branch being pushed
changed_fe_files=$(git diff --name-only @{u}...HEAD 2>/dev/null | grep -E '^frontend/(customer-website|admin-dashboard)/src/.*\.(ts|vue)$' || true)

if [ -z "$changed_fe_files" ]; then
  exit 0
fi

echo "▶ Frontend pre-push checks..."

# Format + lint via lint-staged (auto-fixes are staged automatically)
node "$REPO_ROOT/frontend/node_modules/.bin/lint-staged" \
  --config "$REPO_ROOT/frontend/package.json" \
  --allow-empty

# Type-check each app that has changed files
for app in customer-website admin-dashboard; do
  if echo "$changed_fe_files" | grep -q "^frontend/$app/"; then
    echo "▶ Type-check: $app"
    (cd "$REPO_ROOT/frontend/$app" && npx vue-tsc --build --noEmit)
  fi
done

echo "✓ Frontend checks passed"
