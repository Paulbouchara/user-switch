#!/usr/bin/env bash
# Installs the APK for the owner (user 0) and (re)starts the daemon.
#   scripts/deploy.sh            install + start
#   scripts/deploy.sh --start    start only
set -euo pipefail
cd "$(dirname "$0")/.."

S=$(scripts/phone.sh)
echo "phone: $S"

if [ "${1:-}" != "--start" ]; then
    adb -s "$S" install -r --user 0 build/userswitch.apk
fi

adb -s "$S" shell 'P=$(pm path --user 0 dev.userswitch | head -1 | cut -d: -f2)
[ -n "$P" ] || { echo "app not installed" >&2; exit 1; }
setsid nohup app_process -Djava.class.path=$P /system/bin --nice-name=userswitchd \
    dev.userswitch.daemon.Main > /data/local/tmp/userswitch.log 2>&1 < /dev/null &
echo started'
timeout 3 tail -f /dev/null || true
adb -s "$S" shell cat /data/local/tmp/userswitch.log
