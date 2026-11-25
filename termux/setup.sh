#!/data/data/com.termux/files/usr/bin/bash
# ZAPP-Net — Termux brain setup (one paste, phone becomes the server)
# Run inside Termux:  bash <(cat setup.sh)   or:  bash setup.sh
set -e

echo "== ZAPP-Net: turning this phone into the OpenCode brain =="

# 1. base tools
pkg update -y >/dev/null 2>&1 || true
pkg install -y curl wget nano sqlite >/dev/null 2>&1

# 2. OpenCode CLI (standalone, no node needed)
if ! command -v opencode >/dev/null 2>&1; then
  curl -fsSL https://opencode.ai/install | bash
fi
export PATH="$HOME/.opencode/bin:$PATH"

# 3. listen on the LAN so the ZAPP-Net app (and phone browser) can reach it
opencode service set hostname 0.0.0.0 || true

# 4. wake lock: keep the brain awake while the screen is off
termux-wake-lock

# 5. boot autostart via Termux:Boot
BOOT=~/.termux/boot
mkdir -p "$BOOT"
cat > "$BOOT/zapp-opencode.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
export PATH="$HOME/.opencode/bin:$PATH"
opencode service start
nohup "$HOME/.zapp/unlock.sh" >/dev/null 2>&1 &
EOF
chmod +x "$BOOT/zapp-opencode.sh"

# 6. client-lock watcher (the rotation clears the IP wall; this clears the UI lock).
#    The brain keeps its rate-limit timestamps in drafts.sqlite 'state' rows;
#    wiping them is what actually unblocks the chat UI after each hop.
mkdir -p "$HOME/.zapp"
cat > "$HOME/.zapp/unlock.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
DB="$HOME/.local/share/opencode/drafts.sqlite"
[ -f "$DB" ] || exit 0
while true; do
  sqlite3 "$DB" "DELETE FROM state WHERE key IN ('free-limit','go-upsell','free_usage_limit','usage_limit','quota_exceeded');" 2>/dev/null
  sleep 3
done
EOF
chmod +x "$HOME/.zapp/unlock.sh"
pgrep -f "zapp/unlock.sh" >/dev/null 2>&1 || nohup "$HOME/.zapp/unlock.sh" >/dev/null 2>&1 &

# 7. start now
opencode service start || opencode serve &

sleep 2
PORT=$(opencode service status | grep -oE ':[0-9]+' | tail -1 | tr -d ':')
IP=$(ip -4 addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print $2}')

echo ""
echo "==================================================="
echo " BRAIN IS UP"
echo "   service : $IP:$PORT"
echo "   pair    : run 'opencode pair', then:"
echo "             opencode pair | grep -oE 'http://[^ ]+' | termux-clipboard-set"
echo "             -> ZAPP-Net app shows 'Use link from clipboard' -> one tap"
echo "   sessions survive reboots: files on this phone,"
echo "             Termux:Boot restarts the server"
echo "==================================================="
