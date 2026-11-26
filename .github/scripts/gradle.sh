#!/usr/bin/env bash
# Runs ./gradlew with the given arguments, and runs it once more after a pause when it failed
# while downloading something (Gradle, a plugin or a library): the plugin portal, Maven Central
# and Google's Maven repository all drop requests now and then. A build that fails for any other
# reason fails at once. Used by Android CI and the weekly update check, so that a network hiccup
# neither fails a release nor makes the update check drop an update that builds.
set -uo pipefail

attempts=2
pause=30
network_error='Could not (resolve|GET|HEAD|download)|was not found in any of the following sources|(Read|Connect|connect) timed out|Connection (reset|refused)|Received status code (429|5[0-9][0-9])|Remote host terminated the handshake|java\.net\.(Unknown|Socket|Connect)[A-Za-z]*Exception|Temporary failure in name resolution'
log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$attempts"); do
  ./gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ] && grep -qE "$network_error" "$log"; then
    echo "::warning::Gradle failed while downloading (attempt $attempt of $attempts); trying again in ${pause}s."
    sleep "$pause"
    continue
  fi
  exit "$status"
done
