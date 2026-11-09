#!/usr/bin/env sh
# ActiveDesk installer for Linux, Raspberry Pi and macOS.
#
#   curl -fsSL https://pymite6941.github.io/ActiveDesk/install.sh | sh
#
# What it does, step by step (nothing runs as root except the package installs you approve):
#   1. checks for Java 17+ and offers to install it (free OpenJDK / Temurin)
#   2. checks for Tailscale and offers to install it (free personal plan)
#   3. downloads activedesk.jar from the latest GitHub release and verifies its SHA-256
#   4. puts an `activedesk` command in ~/.local/bin
#   5. starts the setup wizard (`activedesk setup`)
#
# Options (environment variables):
#   ACTIVEDESK_VERSION=v1.2.0   install a specific release instead of the latest
#   ACTIVEDESK_JAR=/path/x.jar  install this local jar instead of downloading (for testing)
#   ACTIVEDESK_YES=1            accept every default without asking
set -eu

REPO="PyMite6941/ActiveDesk"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/activedesk"
BIN_DIR="$HOME/.local/bin"
OS="$(uname -s)"

say()  { printf '  %s\n' "$*"; }
step() { printf '\n  [%s] %s\n' "$1" "$2"; }
die()  { printf '\n  ERROR: %s\n' "$*" >&2; exit 1; }

# Questions read from the terminal even when this script arrives through `curl | sh`.
TTY=/dev/tty
[ -r "$TTY" ] && [ -z "${ACTIVEDESK_YES:-}" ] || TTY=""
ask() { # ask "question" default(y/n)
  if [ -z "$TTY" ]; then [ "$2" = y ]; return; fi
  if [ "$2" = y ]; then printf '  %s [Y/n] ' "$1"; else printf '  %s [y/N] ' "$1"; fi
  read -r a < "$TTY" || a=""
  case "${a:-$2}" in [Yy]*) return 0 ;; *) return 1 ;; esac
}
have() { command -v "$1" >/dev/null 2>&1; }
sudo_() { if [ "$(id -u)" = 0 ]; then "$@"; else sudo "$@"; fi; }

printf '\n  == ActiveDesk installer ==\n'
say "Installs into $APP_DIR (no admin rights needed for ActiveDesk itself)."

# 1. Java ------------------------------------------------------------------------------------------
step 1/5 "Java 17 or newer"
java_ok() {
  # Some distros (e.g. Alpine) install Java under /usr/lib/jvm without putting it on PATH.
  if ! have java; then
    for j in /usr/lib/jvm/*/bin/java; do
      if [ -x "$j" ]; then PATH="$(dirname "$j"):$PATH"; export PATH; break; fi
    done
  fi
  have java || return 1
  v="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1)"
  [ -n "$v" ] && [ "$v" -ge 17 ]
}
if java_ok; then
  say "Found: $(java -version 2>&1 | head -n1)"
else
  say "Java 17+ is not installed. It is free (OpenJDK)."
  if ask "Install it now?" y; then
    if [ "$OS" = Darwin ]; then
      have brew || die "Install Homebrew (https://brew.sh) or Java from https://adoptium.net, then run this again."
      brew install --cask temurin
    elif have apt-get; then   # Debian, Ubuntu, Raspberry Pi OS, Mint, Pop!_OS
      sudo_ apt-get update -qq
      sudo_ apt-get install -y openjdk-17-jre || sudo_ apt-get install -y openjdk-21-jre || sudo_ apt-get install -y default-jre
    elif have dnf; then       # Fedora, RHEL 8+, Rocky, Alma, CentOS Stream
      sudo_ dnf install -y java-21-openjdk || sudo_ dnf install -y java-17-openjdk || sudo_ dnf install -y java-latest-openjdk
    elif have yum; then       # older RHEL / CentOS / Amazon Linux 2
      sudo_ yum install -y java-17-openjdk || sudo_ yum install -y java-17-amazon-corretto
    elif have pacman; then    # Arch, Manjaro, EndeavourOS
      sudo_ pacman -Sy --noconfirm jre-openjdk || sudo_ pacman -Sy --noconfirm jre21-openjdk
    elif have zypper; then    # openSUSE Leap / Tumbleweed
      sudo_ zypper --non-interactive install java-21-openjdk || sudo_ zypper --non-interactive install java-17-openjdk
    elif have apk; then       # Alpine
      sudo_ apk add --no-cache openjdk21-jre || sudo_ apk add --no-cache openjdk17-jre
    elif have xbps-install; then  # Void
      sudo_ xbps-install -Syu xbps || true   # xbps refuses installs while it is itself outdated
      sudo_ xbps-install -Sy openjdk21-jre || sudo_ xbps-install -Sy openjdk17-jre
    else die "Install Java 17+ with your package manager (or from https://adoptium.net), then run this again."
    fi
  fi
  java_ok || die "Java 17+ is still missing. Install it, then run this again."
fi

# 2. Tailscale -------------------------------------------------------------------------------------
step 2/5 "Tailscale (free private network between your devices)"
if have tailscale || [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
  say "Found Tailscale."
else
  say "Tailscale is not installed."
  if [ "$OS" = Darwin ]; then
    say "Install the Tailscale app from https://tailscale.com/download/mac and sign in."
  elif ask "Install it now with Tailscale's official script?" y; then
    curl -fsSL https://tailscale.com/install.sh | sh
    say "Now connect this device:  sudo tailscale up"
    if ask "Run 'sudo tailscale up' now (opens a sign-in link)?" y; then sudo_ tailscale up || true; fi
  fi
fi

# 3. Download ---------------------------------------------------------------------------------------
step 3/5 "Download ActiveDesk"
mkdir -p "$APP_DIR" "$BIN_DIR"
if [ -n "${ACTIVEDESK_JAR:-}" ]; then
  cp "$ACTIVEDESK_JAR" "$APP_DIR/activedesk.jar.new"
  say "Using local jar $ACTIVEDESK_JAR"
else
  if [ -n "${ACTIVEDESK_VERSION:-}" ]; then base="https://github.com/$REPO/releases/download/$ACTIVEDESK_VERSION"
  else base="https://github.com/$REPO/releases/latest/download"; fi
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/activedesk.jar" "$base/activedesk.jar" || die "Download failed ($base/activedesk.jar)."
  curl -fsSL -o "$tmp/SHA256SUMS.txt" "$base/SHA256SUMS.txt" || die "Could not download the checksum file."
  want="$(grep ' activedesk.jar$' "$tmp/SHA256SUMS.txt" | cut -d' ' -f1)"
  if have sha256sum; then got="$(sha256sum "$tmp/activedesk.jar" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$tmp/activedesk.jar" | cut -d' ' -f1)"; fi
  [ -n "$want" ] && [ "$want" = "$got" ] || die "Checksum mismatch: the download is corrupt or was tampered with. Nothing was installed."
  say "Checksum OK ($got)"
  mv "$tmp/activedesk.jar" "$APP_DIR/activedesk.jar.new"
fi
mv "$APP_DIR/activedesk.jar.new" "$APP_DIR/activedesk.jar"
mkdir -p "$APP_DIR/lib"

# 4. Command ---------------------------------------------------------------------------------------
step 4/5 "The 'activedesk' command"
cat > "$BIN_DIR/activedesk" <<EOF
#!/usr/bin/env sh
# created by the ActiveDesk installer
exec "$(command -v java)" -cp "$APP_DIR/activedesk.jar:$APP_DIR/lib/*" dev.activedesk.ActiveDesk "\$@"
EOF
chmod 755 "$BIN_DIR/activedesk"
say "Installed $BIN_DIR/activedesk"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) say "Add it to your PATH (then open a new terminal):  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.profile" ;;
esac

# 5. Setup wizard ----------------------------------------------------------------------------------
step 5/5 "Setup wizard"
if [ -n "$TTY" ]; then "$BIN_DIR/activedesk" setup < "$TTY"; else "$BIN_DIR/activedesk" setup --yes; fi
