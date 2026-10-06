#!/usr/bin/env bash
# Prints the adb serial of the phone, reconnecting if needed.
# GrapheneOS changes the phone's IP when Wi-Fi reconnects (e.g. after a user
# switch), so when the last known address fails, the LAN is scanned for the
# legacy adb TCP port (enable it once with `adb tcpip 5555`).
set -uo pipefail

PORT=${ADB_PORT:-5555}
SUBNET=${SUBNET:-192.168.1.0/24}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/userswitch-phone

try() {
    timeout 5 adb connect "$1" 2>/dev/null | grep -q "connected to" &&
        [ "$(timeout 5 adb -s "$1" shell echo ok 2>/dev/null | tr -d '\r')" = ok ]
}

# A phone plugged in over USB needs none of the network dance.
usb=$(adb devices | awk 'NR>1 && $2=="device" && $1!~/:/{print $1; exit}')
if [ -n "$usb" ]; then
    echo "$usb"
    exit 0
fi

if [ -f "$CACHE" ] && try "$(cat "$CACHE")"; then
    cat "$CACHE"
    exit 0
fi

for ip in $(timeout 90 nmap -p "$PORT" --open -T4 -oG - "$SUBNET" 2>/dev/null | awk '/Ports:.*open/{print $2}'); do
    if try "$ip:$PORT"; then
        mkdir -p "$(dirname "$CACHE")"
        echo "$ip:$PORT" | tee "$CACHE"
        exit 0
    fi
done

echo "phone not found on $SUBNET:$PORT" >&2
exit 1
