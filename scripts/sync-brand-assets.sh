#!/usr/bin/env bash
# Copies the design pack (docs/design) into the files the console serves (api/src/main/resources/static/brand), ADR-040.
# Run it after the design tool exports a new docs/design/tokens.css, then commit both. BrandTokensInSyncTest fails the build when the
# two are not the same. The copy is never edited by hand.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pack="$root/docs/design"
brand="$root/api/src/main/resources/static/brand"

mkdir -p "$brand/fonts" "$brand/logo" "$brand/icons"

cp "$pack"/fonts/*.woff2 "$pack"/fonts/OFL-LICENSE.txt "$brand/fonts/"
cp "$pack"/logo/*.svg "$brand/logo/"
cp "$pack"/logo/favicon.ico "$brand/favicon.ico"
cp "$pack"/logo/icon-*.png "$brand/icons/"

# tokens.css: the font paths are already relative to the file (fonts/...), which is where /brand/fonts/ is; only the first line, the
# comment that says where the file lives, is replaced.
{
  printf '%s\n' "/* Listing Guard design tokens (Design System v2, 2026-11-15). A copy of docs/design/tokens.css, kept equal by BrandTokensInSyncTest; edit tokens.json in the design pack, never this file (ADR-040). Font paths are relative to this file, so /brand/fonts/ holds the files. */"
  tail -n +2 "$pack/tokens.css" | tr -d '\r'
} > "$brand/tokens.css"

echo "Copied $pack to $brand. Now run: ./mvnw -pl api -am -Dtest=BrandTokensInSyncTest test"
