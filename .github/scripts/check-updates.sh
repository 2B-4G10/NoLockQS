#!/usr/bin/env bash
# Weekly check for the bumps that follow new Android and toolchain releases, which Dependabot
# cannot see:
#  - a newer stable Android SDK platform than compileSdk/targetSdk,
#  - a newer Gradle release than the wrapper,
#  - a new libxposed API level.
# SDK and Gradle bumps are applied, verified with a full build and pushed to main. Anything that
# needs a person (a failing build, a new API level to review, testing on a device) is reported as
# a GitHub issue.
#
# Expects: a checkout of main with push access, SDKMANAGER (from setup-toolchain.sh), gh with
# GH_TOKEN and GH_REPO, and RUNNER_TEMP.
set -euo pipefail

BUILD_FILE=app/build.gradle.kts
WRAPPER=gradle/wrapper/gradle-wrapper.properties
CATALOG=gradle/libs.versions.toml
changes=()
new_sdk=""

# True when version $1 is newer than version $2.
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

# Opens an issue unless an open one with the same title exists.
open_issue() {
  if gh issue list --state open --limit 200 --json title --jq '.[].title' | grep -Fxq "$1"; then
    echo "Issue already open: $1"
  else
    gh issue create --title "$1" --body "$2"
  fi
}

# 1. Android SDK: move compileSdk and targetSdk to the newest stable platform.
compile_sdk=$(sed -n 's/.*version = release(\([0-9]*\)).*/\1/p' "$BUILD_FILE")
target_sdk=$(sed -n 's/^ *targetSdk = \([0-9]*\)$/\1/p' "$BUILD_FILE")
latest_sdk=$("$SDKMANAGER" --list 2>/dev/null | sed -n 's/^ *platforms;android-\([0-9][0-9]*\) .*/\1/p' | sort -n | tail -1)
echo "Android SDK: compileSdk $compile_sdk, targetSdk $target_sdk, newest stable ${latest_sdk:-unknown}"
if [ -n "$latest_sdk" ] && [ "$latest_sdk" -gt "$compile_sdk" ]; then
  sed -i -e "s/version = release($compile_sdk)/version = release($latest_sdk)/" \
         -e "s/^\( *targetSdk = \)$target_sdk$/\1$latest_sdk/" "$BUILD_FILE"
  changes+=("target Android API $latest_sdk (was $compile_sdk)")
  new_sdk=$latest_sdk
fi

# 2. Gradle wrapper, with the distribution checksum pinned.
gradle_now=$(sed -n 's#^distributionUrl=.*/gradle-\(.*\)-bin\.zip$#\1#p' "$WRAPPER")
gradle_latest=$(curl -fsS https://services.gradle.org/versions/current | jq -r .version)
echo "Gradle: wrapper $gradle_now, newest $gradle_latest"
if newer "$gradle_latest" "$gradle_now"; then
  checksum=$(curl -fsSL "https://services.gradle.org/distributions/gradle-$gradle_latest-bin.zip.sha256")
  # The first run switches the version; the second regenerates the wrapper files with it.
  for _ in 1 2; do
    ./gradlew --quiet wrapper --gradle-version "$gradle_latest" --gradle-distribution-sha256-sum "$checksum"
  done
  changes+=("Gradle $gradle_latest (was $gradle_now)")
fi

# 3. libxposed: a new API level can change the hook interface, so it is only reported.
xposed_now=$(sed -n 's/^libxposed = "\([0-9]*\)\..*/\1/p' "$CATALOG")
xposed_latest=$(curl -fsS https://repo1.maven.org/maven2/io/github/libxposed/api/maven-metadata.xml \
  | sed -n 's:.*<release>\([0-9]*\)\..*</release>.*:\1:p')
echo "libxposed API: using $xposed_now, newest ${xposed_latest:-unknown}"
if [ -n "$xposed_latest" ] && [ "$xposed_latest" -gt "$xposed_now" ]; then
  open_issue "libxposed API $xposed_latest is available" "libxposed API $xposed_latest is out; NoLockQS uses API $xposed_now. A new API level can change the hook interface, so it is not updated automatically.

- [ ] Update \`libxposed\` in \`gradle/libs.versions.toml\`
- [ ] Update \`targetApiVersion\` in \`app/src/main/resources/META-INF/xposed/module.prop\`
- [ ] Check the libxposed changelog for changes to the hooks the module uses
- [ ] Test on a device, then bump \`version\` and \`versionCode\` in \`module.prop\` to release"
fi

if [ ${#changes[@]} -eq 0 ]; then
  echo "Everything is up to date."
  exit 0
fi

summary=$(printf '%s\n' "${changes[@]}" | sed 's/^/- /')
log="$RUNNER_TEMP/update-build.log"
if ! ./gradlew :app:assembleDebug :app:assembleRelease --stacktrace > "$log" 2>&1; then
  open_issue "Automated update does not build" "The weekly update check tried these changes, but the build failed, so nothing was pushed:

$summary

<details><summary>End of the build log</summary>

\`\`\`
$(tail -n 60 "$log")
\`\`\`

</details>

The check runs again next week. A newer Android Gradle Plugin from Dependabot often fixes this."
  exit 1
fi

if [ ${#changes[@]} -eq 1 ]; then title="build: ${changes[0]}"; else title="build: update the Android SDK and Gradle"; fi
git add -- "$BUILD_FILE" gradle/wrapper gradlew gradlew.bat
git commit -q -m "$title" -m "Automated weekly update check; the debug and release builds pass.

$summary"
git pull -q --rebase origin main
git push -q origin HEAD:main
echo "Pushed: $title"

if [ -n "$new_sdk" ]; then
  open_issue "Android API $new_sdk: test NoLockQS on a device" "The build now targets Android API $new_sdk ($(git rev-parse --short HEAD)) and it compiles, but the hooks can only be checked on a phone running it:

- [ ] Quick Settings can't be pulled down on the lock screen, in portrait and landscape
- [ ] The power menu doesn't open on the lock screen
- [ ] Both work normally once unlocked
- [ ] The module screen shows \"Module active\"
- [ ] Update \"tested on Android …\" in the README

Then bump \`version\` and \`versionCode\` in \`app/src/main/resources/META-INF/xposed/module.prop\` to release."
fi
