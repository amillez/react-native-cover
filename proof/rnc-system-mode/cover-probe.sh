#!/bin/zsh
# Usage: cover-probe.sh <serial> <state|recents <png>|cycles <n>|crashes>
SERIAL=$1; CMD=$2
PKG=${PKG:-com.peersyst.nearmobilewallet}
A=(adb -s $SERIAL)

pid() { $A shell pidof $PKG | tr -d '\r'; }

state() {
  local p=$(pid)
  echo "pid=$p"
  echo "cover_windows=$($A shell dumpsys window windows | grep -o "Window{[0-9a-f]* u0 Panel:${PKG}[^}]*}" | sort -u | wc -l | tr -d " ")"
  echo "receiver_threads=$($A shell ps -T -p $p | grep -c CoverSystemDial)"
  echo "image_threads=$($A shell ps -T -p $p | grep -c 'cover.image')"
}

recents() {
  $A shell input keyevent KEYCODE_APP_SWITCH
  sleep 2
  $A exec-out screencap -p > $1
  echo "saved $1"
}

cycles() {
  $A logcat -c
  local n=$1 i
  for i in $(seq 1 $n); do
    $A shell input keyevent KEYCODE_HOME; sleep 1
    $A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 1.5
    $A shell input keyevent KEYCODE_APP_SWITCH; sleep 1.2
    $A shell input keyevent KEYCODE_BACK; sleep 1.5
  done
  crashes
  echo "cover_log_mounts=$($A logcat -d -s Cover | grep -c 'performUserLeaveMount\|onStopped: count=0')"
  echo "broadcast_reasons:"; $A logcat -d -s Cover | grep -o 'broadcast: reason=[a-z_]*' | sort | uniq -c
}

crashes() {
  echo "fatal=$($A logcat -d -b crash | grep -c 'FATAL EXCEPTION')"
  echo "anr=$($A logcat -d | grep -c 'ANR in '$PKG)"
  echo "invalid_token=$($A logcat -d | grep -c 'Invalid window token')"
  echo "pid_after=$(pid)"
}

case $CMD in
  state) state ;;
  recents) recents $3 ;;
  cycles) cycles ${3:-10} ;;
  crashes) crashes ;;
esac
