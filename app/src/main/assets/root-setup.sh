#!/system/bin/sh
set -e
BASE=/data/local/tmp/niofirefly-standalone
STATE="$BASE/state"
mkdir -p "$BASE"
get_value() { sed -n "s/^$1=//p" "$STATE" | head -n 1; }
save_setting() { printf '%s=%s\n' "$1" "$(settings get system "$1")"; }
restore_setting() {
  value=$(get_value "$1")
  if [ "$value" = null ] || [ -z "$value" ]; then settings delete system "$1" >/dev/null 2>&1 || true
  else settings put system "$1" "$value"; fi
}
case "$1" in
  install)
    cp "$2" "$BASE/scrcpy-server.jar"
    chmod 644 "$BASE/scrcpy-server.jar"
    cp "$0" "$BASE/root-setup.sh"
    chmod 700 "$BASE/root-setup.sh"
    ;;
  start)
    if [ -f /data/adb/niofirefly/manual-stop.sh ]; then
      sh /data/adb/niofirefly/manual-stop.sh >/dev/null 2>&1 || true
    fi
    if [ ! -f "$STATE" ]; then
      {
        printf 'wm_size=%s\n' "$(wm size | sed -n 's/^Override size: //p')"
        printf 'wm_density=%s\n' "$(wm density | sed -n 's/^Override density: //p')"
        save_setting accelerometer_rotation
        save_setting user_rotation
        save_setting screen_off_timeout
        save_setting screen_brightness
        save_setting screen_brightness_mode
        for path in /sys/class/backlight/*/brightness; do
          if [ -f "$path" ]; then
            printf 'backlight_path=%s\n' "$path"
            printf 'backlight_value=%s\n' "$(cat "$path" 2>/dev/null)"
            break
          fi
        done
      } > "$STATE"
    fi
    if [ "$6" != 1 ]; then
      settings put system accelerometer_rotation 0
      settings put system user_rotation 1
      settings put system screen_off_timeout 2147483647
      if [ "$5" = 1 ]; then
        wm size "$2"
        wm density "$3"
      fi
    fi
    if [ -n "$4" ]; then
      (sh "$BASE/root-setup.sh" watch "$4") </dev/null >/dev/null 2>&1 &
    fi
    ;;
  dim)
    path=$(get_value backlight_path)
    if [ -n "$path" ] && [ -f "$path" ]; then echo 0 > "$path" 2>/dev/null || true; fi
    settings put system screen_brightness_mode 0
    settings put system screen_brightness 0
    ;;
  wake)
    path=$(get_value backlight_path)
    value=$(get_value backlight_value)
    if [ -n "$path" ] && [ -n "$value" ] && [ -f "$path" ]; then echo "$value" > "$path" 2>/dev/null || true; fi
    restore_setting screen_brightness
    restore_setting screen_brightness_mode
    ;;
  stop)
    if [ ! -f "$STATE" ]; then exit 0; fi
    path=$(get_value backlight_path)
    value=$(get_value backlight_value)
    if [ -n "$path" ] && [ -n "$value" ] && [ -f "$path" ]; then echo "$value" > "$path" 2>/dev/null || true; fi
    size=$(get_value wm_size)
    density=$(get_value wm_density)
    if [ -n "$size" ]; then wm size "$size"; else wm size reset; fi
    if [ -n "$density" ]; then wm density "$density"; else wm density reset; fi
    restore_setting accelerometer_rotation
    restore_setting user_rotation
    restore_setting screen_off_timeout
    restore_setting screen_brightness
    restore_setting screen_brightness_mode
    rm -f "$STATE"
    ;;
  watch)
    while [ -f "$STATE" ] && kill -0 "$2" 2>/dev/null; do sleep 5; done
    if [ -f "$STATE" ]; then sh "$BASE/root-setup.sh" stop; fi
    ;;
  *) echo 'Usage: root-setup.sh install|start|dim|wake|stop' >&2; exit 2 ;;
esac
