#!/usr/bin/env bash
#
# Open every page the diary sends, on this machine, as it sends it.
#
# The tablet archives each rasterised page and logs where it put it; this
# follows that log, pulls the file over, and opens it. Run it in a terminal
# and leave it running while you write.
#
#   scripts/watch-pages.sh              # pull and open each page
#   scripts/watch-pages.sh --no-open    # pull only, into ./pages
#
set -euo pipefail

open_them=1
[ "${1:-}" = "--no-open" ] && open_them=0

dest="$(cd "$(dirname "$0")/.." && pwd)/pages"
mkdir -p "$dest"

command -v adb >/dev/null || { echo "adb không có trong PATH" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "không thấy máy nào qua adb" >&2; exit 1; }

echo "đang theo dõi… trang sẽ về $dest (Ctrl-C để dừng)"
adb logcat -c
adb logcat -s RiddleStateMachine | while IFS= read -r line; do
    case "$line" in
        *"page sent, archived at "*)
            remote="${line##*archived at }"
            file="$dest/$(basename "$remote")"
            if adb pull "$remote" "$file" >/dev/null 2>&1; then
                echo "$(basename "$file")"
                [ "$open_them" = 1 ] && open "$file"
            fi
            ;;
    esac
done
