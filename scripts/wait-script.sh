#!/usr/bin/env bash

IFS="," read -ra PORTS <<<"${WAIT_PORTS:-8080,8070,8060,8050}"
path=$(dirname "$0")

PIDs=()
for port in "${PORTS[@]}"; do
  bash "$path"/wait-for.sh -t 120 "http://localhost:$port/manage/health" -- echo "Host localhost:$port is active" &
  PIDs+=($!)
done

for pid in "${PIDs[@]}"; do
  if ! wait "${pid}"; then
    exit 1
  fi
done
