#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAGES_DIR="$ROOT_DIR/pages"
PROJECT_NAME="${RIDDLEBOOX_CF_PROJECT:-riddleboox}"
CUSTOM_DOMAIN="${RIDDLEBOOX_CF_DOMAIN:-riddleboox.aiocean.io}"

if ! command -v wrangler >/dev/null 2>&1; then
  echo "wrangler is required. Install/login to Cloudflare before deploying." >&2
  exit 1
fi

if [[ ! -s "$PAGES_DIR/index.html" ]]; then
  echo "Missing static entry point: $PAGES_DIR/index.html" >&2
  exit 1
fi

for file in "$PAGES_DIR/styles.css" "$PAGES_DIR/script.js"; do
  if [[ ! -s "$file" ]]; then
    echo "Missing static asset: $file" >&2
    exit 1
  fi
done

if ! compgen -G "$PAGES_DIR/page-*.png" >/dev/null; then
  echo "No handwriting proof assets found in $PAGES_DIR" >&2
  exit 1
fi

run_wrangler() {
  if [[ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ]]; then
    CLOUDFLARE_ACCOUNT_ID="$CLOUDFLARE_ACCOUNT_ID" wrangler "$@"
  else
    wrangler "$@"
  fi
}

echo "Checking Cloudflare authentication..."
run_wrangler whoami
echo "Deploying $PAGES_DIR to Pages project '$PROJECT_NAME'..."
run_wrangler pages deploy "$PAGES_DIR" \
  --project-name="$PROJECT_NAME" \
  --branch=main \
  --commit-dirty=true

echo
echo "Production: https://$PROJECT_NAME.pages.dev"
echo "Custom domain: https://$CUSTOM_DOMAIN"
