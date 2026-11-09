#!/bin/sh
# Runs inside a fresh distro container (as root, no Java) from the `distros` CI job:
# the real install.sh must install Java with that distro's package manager, install
# ActiveDesk, run the setup wizard, and the installed command must pass the self-test.
set -eu
cd /w

# Stand-in so the container doesn't really install Tailscale (no tun device here).
mkdir -p /tmp/fake
printf '#!/bin/sh\nexit 1\n' > /tmp/fake/tailscale
chmod +x /tmp/fake/tailscale

export ACTIVEDESK_YES=1 ACTIVEDESK_JAR=/w/dist/activedesk.jar ACTIVEDESK_HOME=/tmp/adhome
PATH="/tmp/fake:$PATH" sh /w/install.sh

AD="$HOME/.local/bin/activedesk"
"$AD" version
grep -q 'web.pinHash=' "$ACTIVEDESK_HOME/config.properties"
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true "$AD" selftest
