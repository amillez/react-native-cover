#!/bin/zsh
# Usage: race-trials.sh <serial> <label> <n> <outdir>
A=(adb -s $1); mkdir -p $4
covered=0
for i in $(seq 1 $3); do
  $A shell input keyevent KEYCODE_HOME; sleep 1.5
  $A shell input keyevent KEYCODE_APP_SWITCH; sleep 2
  $A exec-out screencap -p > $4/$2-$i.png
  sd=$(magick $4/$2-$i.png -crop 680x240+200+860 -colorspace gray -format '%[fx:standard_deviation]' info:)
  if (( sd < 0.01 )); then verdict=cover; covered=$((covered+1)); else verdict=EXPOSED; fi
  echo "$2 trial=$i sd=$sd $verdict"
  $A shell input tap 538 1200; sleep 2.5
done
echo "$2 covered=$covered/$3"
