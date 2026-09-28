#!/usr/bin/env bash
# Prepares a GitHub Ubuntu runner using only its preinstalled tools: selects the JDK pinned in
# gradle/gradle-daemon-jvm.properties and accepts new Android SDK licenses, so AGP can download
# the platform and build-tools it needs. Exports SDKMANAGER for later steps.
set -euo pipefail

java=$(sed -n 's/^toolchainVersion=//p' gradle/gradle-daemon-jvm.properties | tr -d '[:space:]')
jdk_var="JAVA_HOME_${java}_X64"
if [ -n "${!jdk_var:-}" ]; then
  echo "JAVA_HOME=${!jdk_var}" >> "$GITHUB_ENV"
else
  echo "::notice::JDK $java is not preinstalled on this runner; Gradle provisions it."
fi

sdkmanager=$(find "$ANDROID_HOME/cmdline-tools" -path '*/bin/sdkmanager' 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$sdkmanager" ]; then
  yes | "$sdkmanager" --licenses > /dev/null || true
  echo "SDKMANAGER=$sdkmanager" >> "$GITHUB_ENV"
fi
