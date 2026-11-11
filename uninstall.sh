#!/usr/bin/env sh
# ActiveDesk uninstaller for Linux, Raspberry Pi and macOS.
#
#   curl -fsSL https://pymite6941.github.io/ActiveDesk/uninstall.sh | sh
#
# Runs `activedesk uninstall --yes`, which stops ActiveDesk, puts the battery back to normal charging,
# removes the power setup (asks for your password only if it was installed), the autostart entries,
# the `activedesk` command and the program folder. Your data folder (~/.activedesk) is kept.
#
# If the program folder is already gone, it fetches the latest release to a temp folder, verifies its
# SHA-256 and runs the uninstaller from there, so leftovers still get cleaned.
#
# Options (environment variables):
#   ACTIVEDESK_PURGE=1   also delete your data folder (config, PIN, chat history, logs)
set -eu

REPO="PyMite6941/ActiveDesk"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/activedesk"
say() { printf '  %s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

printf '\n  == ActiveDesk uninstaller ==\n\n'
if ! have java; then
  for j in /usr/lib/jvm/*/bin/java; do
    if [ -x "$j" ]; then PATH="$(dirname "$j"):$PATH"; break; fi
  done
fi
if ! have java; then
  say "Java is not installed, so ActiveDesk cannot be running. To clean up by hand, delete:"
  say "  $APP_DIR  ~/.local/bin/activedesk  ~/.config/autostart/activedesk.desktop"
  say "  ~/.config/systemd/user/activedesk.service  ~/Library/LaunchAgents/dev.activedesk.plist"
  say "and, if you ran 'activedesk power install':  sudo rm -f /etc/udev/rules.d/90-activedesk-power.rules /etc/sudoers.d/activedesk"
  exit 1
fi

jar="$APP_DIR/activedesk.jar"
tmp=""
if [ ! -f "$jar" ]; then
  say "The installed copy is gone; fetching the latest release to run the uninstaller from a temp folder."
  base="https://github.com/$REPO/releases/latest/download"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/activedesk.jar" "$base/activedesk.jar" || { say "Download failed ($base/activedesk.jar)."; exit 1; }
  curl -fsSL -o "$tmp/SHA256SUMS.txt" "$base/SHA256SUMS.txt" || { say "Could not download the checksum file."; exit 1; }
  want="$(grep ' activedesk.jar$' "$tmp/SHA256SUMS.txt" | cut -d' ' -f1)"
  if have sha256sum; then got="$(sha256sum "$tmp/activedesk.jar" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$tmp/activedesk.jar" | cut -d' ' -f1)"; fi
  if [ -z "$want" ] || [ "$want" != "$got" ]; then say "Checksum mismatch: the download is corrupt or was tampered with. Nothing was changed."; exit 1; fi
  jar="$tmp/activedesk.jar"
fi

set -- uninstall --yes
[ "${ACTIVEDESK_PURGE:-}" = 1 ] && set -- "$@" --purge
# stdin from the terminal so sudo can ask for a password even under `curl | sh`
if [ -r /dev/tty ]; then java -cp "$jar" dev.activedesk.ActiveDesk "$@" < /dev/tty; else java -cp "$jar" dev.activedesk.ActiveDesk "$@"; fi
