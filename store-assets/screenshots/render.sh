#!/usr/bin/env bash
# Render screenshots.html thành PNG cho Play: 1440x2560 (tablet 10") và 1080x1920 (phone, tablet 7").
# Cần Playwright headless shell (ms-playwright cache) và ImageMagick (`magick`).
set -euo pipefail
cd "$(dirname "$0")"
BIN=$(ls -d ~/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-mac-arm64/chrome-headless-shell | tail -1)
NAMES=(reply agent library agents history key)
for i in 1 2 3 4 5 6; do
  out="screenshot-$i-${NAMES[$((i-1))]}"
  "$BIN" --headless --user-data-dir=/tmp/riddleboox-shots-profile --no-first-run --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=1 --window-size=1440,2560 --virtual-time-budget=10000 \
    --screenshot="$out.png" "file://$PWD/screenshots.html?shot=$i" 2>/dev/null
  magick "$out.png" -resize 1080x1920 "phone/$out.png"
done
