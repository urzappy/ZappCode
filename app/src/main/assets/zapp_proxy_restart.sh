#!/data/data/com.termux/files/usr/bin/sh
# Swap the live Termux-side WARP proxy for $HOME/zapp_proxy.py and prove the new code ran.
OUT="$1"
PORT="${2:-41988}"
SRC="${3:-/sdcard/zapp_proxy_new.py}"
LOG="$HOME/zapp_proxy.out"
{
  echo "== install"
  cp "$SRC" "$HOME/zapp_proxy.py" && chmod 700 "$HOME/zapp_proxy.py"
  echo "sha=$(md5sum "$HOME/zapp_proxy.py" | cut -d' ' -f1)"
  echo "bytes=$(wc -c < "$HOME/zapp_proxy.py")"

  echo "== kill attempts"
  for pid in $(run-as com.termux ps -A -o PID,ARGS 2>/dev/null | grep '[z]app_proxy.py' | awk '{print $1}'); do
    kill -9 "$pid" 2>/dev/null && echo "killed $pid" || { run-as com.termux kill -9 "$pid" 2>/dev/null && echo "runas-killed $pid" || echo "failed $pid"; }
  done
  for pid in $(ps -A -o PID,ARGS 2>/dev/null | grep '[z]app_proxy.py' | awk '{print $1}'); do
    kill -9 "$pid" 2>/dev/null && echo "killed-local $pid"
  done
  pkill -9 -f zapp_proxy.py 2>/dev/null && echo "pkill hit"
  sleep 2

  echo "== survivors"
  run-as com.termux ps -A -o PID,ARGS 2>/dev/null | grep '[z]app_proxy.py' || echo none
  echo "== port "
  python3 - "$PORT" <<'PY'
import socket, sys
s = socket.socket(); s.settimeout(1.0)
print("open" if s.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0 else "closed")
s.close()
PY

  echo "== start"
  : > "$LOG"
  nohup python3 "$HOME/zapp_proxy.py" "$PORT" >> "$LOG" 2>&1 &
  echo "spawned pid=$!"
  sleep 3
  echo "== banner"
  head -3 "$LOG"
} > "$OUT" 2>&1
echo done >> "$OUT"
