#!/usr/bin/busybox sh
PS='$ '
while true; do
  echo -n "$PS"
  read cmd
  eval "$cmd"
done
