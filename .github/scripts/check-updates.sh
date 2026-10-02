#!/usr/bin/env bash
# Weekly check for the updates that follow new Android and toolchain releases:
#  - the Gradle wrapper, with the distribution checksum pinned,
#  - the Android Gradle Plugin, to its newest stable release,
#  - compileSdk and targetSdk, to the newest stable Android SDK platform (compileSdk to its newest
#    minor version too),
#  - libxposed (api and service, kept on one version), with targetApiVersion in module.prop when
#    its API level changes.
# Each update is applied on top of the previous ones and kept only if the debug and release builds
# still pass; the ones that build are pushed to main together. An update that can't be applied is
# left out and reported as a GitHub issue, and so is a new Android or libxposed API level, whose
# hooks can only be tested on a device.
#
# Expects: a checkout of main with push access, SDKMANAGER (from setup-toolchain.sh), gh with
# GH_TOKEN and GH_REPO, and RUNNER_TEMP.
set -euo pipefail

BUILD_FILE=app/build.gradle.kts
WRAPPER=gradle/wrapper/gradle-wrapper.properties
CATALOG=gradle/libs.versions.toml
MODULE_PROP=app/src/main/resources/META-INF/xposed/module.prop
updated=()       # "<name> <version>" of each update that builds
summary=()       # the same, with the version it replaces
failed=()        # "<name> <version>" of each update that was left out
device_tests=()  # new API levels to test on a device

# True when version $1 is newer than version $2.
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

# The stable versions (digits and dots only) in a Maven metadata file, one per line, or nothing if
# it can't be read. <release> is not used: Google's repository points it at previews.
stable_versions() {
  { curl -fsS --retry 3 "$1" || true; } \
    | { grep -oE '<version>[0-9]+(\.[0-9]+)*</version>' || true; } \
    | sed 's/<[^>]*>//g' | sort -u
}

newest() { sort -V | tail -1; }

# "a", "a and b", "a, b and c".
join_and() {
  local out=$1
  shift
  while [ $# -gt 1 ]; do out+=", $1"; shift; done
  if [ $# -eq 1 ]; then out+=" and $1"; fi
  printf '%s' "$out"
}

# Opens an issue unless an open one with the same title exists.
open_issue() {
  local titles
  titles=$(gh issue list --state open --limit 200 --json title --jq '.[].title') || titles=""
  if grep -Fxq -- "$1" <<< "$titles"; then
    echo "Issue already open: $1"
  else
    gh issue create --title "$1" --body "$2"
  fi
}

# Updates <name> from <old> to <new> with the command that follows, then builds. The update is
# kept if the debug and release builds pass; otherwise the files go back to the last state that
# built and an issue reports it. Returns whether the update was kept.
try_update() {
  local name=$1 new=$2 old=$3 log="$RUNNER_TEMP/update.log" problem details=""
  shift 3
  if ! "$@" > "$log" 2>&1; then
    problem="applying it failed"
  elif git diff --quiet; then
    problem="the version was not where the check expects it"
  elif ! ./gradlew :app:assembleDebug :app:assembleRelease --stacktrace >> "$log" 2>&1; then
    problem="the build failed"
  else
    git add -u
    updated+=("$name $new")
    summary+=("$name $new (was $old)")
    echo "$name: updated to $new"
    return 0
  fi
  git checkout -q -- .
  failed+=("$name $new")
  echo "::warning::$name $new was left out: $problem."
  if [ -s "$log" ]; then
    details=$'\n\n<details><summary>End of the log</summary>\n\n```\n'"$(tail -n 60 "$log")"$'\n```\n\n</details>'
  fi
  open_issue "Could not update $name to $new" "The weekly update check tried to update $name from $old to $new, but $problem, so this update was left out. Any other update that built was still pushed to main.$details

The check tries again every week, and a newer release often fixes this. Close this issue once $name is updated on main."
  return 1
}

update_gradle() {
  # The first run switches the version; the second regenerates the wrapper files with it.
  for _ in 1 2; do
    ./gradlew --quiet wrapper --gradle-version "$1" --gradle-distribution-sha256-sum "$2" || return 1
  done
}

set_catalog_version() { sed -i "s/^$1 = \".*\"$/$1 = \"$2\"/" "$CATALOG"; }

# compileSdk takes the platform's major and minor version, written on one line as
# "release(37) { minorApiLevel = 2 }"; targetSdk only has major versions.
set_sdk() {
  local version="release($1)"
  if [ "$2" -gt 0 ]; then version+=" { minorApiLevel = $2 }"; fi
  sed -i -e "s/version = release(.*/version = $version/" \
         -e "s/^\( *targetSdk = \)[0-9]*$/\1$1/" "$BUILD_FILE"
}

# libxposed's major version is its API level, which module.prop declares as targetApiVersion.
# minApiVersion stays: it is the oldest API the module's code needs.
set_libxposed() {
  set_catalog_version libxposed "$1" \
    && sed -i "s/^targetApiVersion=.*/targetApiVersion=${1%%.*}/" "$MODULE_PROP"
}

# 1. Gradle wrapper. First, as a new Android Gradle Plugin can need it.
gradle_now=$(sed -n 's#^distributionUrl=.*/gradle-\(.*\)-bin\.zip$#\1#p' "$WRAPPER")
gradle_latest=$({ curl -fsS --retry 3 https://services.gradle.org/versions/current || true; } \
  | jq -r '.version // empty' 2>/dev/null || true)
echo "Gradle: using $gradle_now, newest ${gradle_latest:-unknown}"
if [ -z "$gradle_latest" ]; then
  echo "::warning::Could not read the newest Gradle version."
elif newer "$gradle_latest" "$gradle_now"; then
  if checksum=$(curl -fsSL --retry 3 "https://services.gradle.org/distributions/gradle-$gradle_latest-bin.zip.sha256"); then
    try_update Gradle "$gradle_latest" "$gradle_now" update_gradle "$gradle_latest" "$checksum" || true
  else
    echo "::warning::Could not read the checksum of Gradle $gradle_latest."
  fi
fi

# 2. Android Gradle Plugin. Before the SDK, as a new SDK level can need it.
agp_now=$(sed -n 's/^agp = "\(.*\)"$/\1/p' "$CATALOG")
agp_latest=$(stable_versions https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml | newest)
echo "Android Gradle Plugin: using $agp_now, newest stable ${agp_latest:-unknown}"
if [ -z "$agp_latest" ]; then
  echo "::warning::Could not read the Android Gradle Plugin versions from Google's Maven repository."
elif newer "$agp_latest" "$agp_now"; then
  try_update "Android Gradle Plugin" "$agp_latest" "$agp_now" set_catalog_version agp "$agp_latest" || true
fi

# 3. Android SDK. Platforms are listed as "android-36" or, since API 37, with a minor version
#    ("android-37.2"); betas, canaries and extension packages carry a suffix and are skipped.
#    Newer command-line tools separate the package path with "/" instead of ";". Levels are
#    compared as "<major>.<minor>", so "android-36" counts as 36.0. Only a new major level changes
#    targetSdk, and with it how the app behaves, so only that one needs a test on a device.
compile_sdk=$(sed -n 's/.*version = release(\([0-9]*\)).*/\1/p' "$BUILD_FILE")
compile_minor=$(sed -n 's/.*version = release([0-9]*) { minorApiLevel = \([0-9]*\) }.*/\1/p' "$BUILD_FILE")
target_sdk=$(sed -n 's/^ *targetSdk = \([0-9]*\)$/\1/p' "$BUILD_FILE")
latest_level=$({ "$SDKMANAGER" --list 2>/dev/null || true; } \
  | sed -En 's/^ *platforms[;\/]android-([0-9]+)(\.([0-9]+))?[[:space:]].*/\1.\3/p' \
  | sed 's/\.$/.0/' | sort -V | tail -1)
compile_level=${compile_sdk:+$compile_sdk.${compile_minor:-0}}
latest_sdk=${latest_level%%.*}
echo "Android SDK: compileSdk ${compile_level:-unknown}, targetSdk ${target_sdk:-unknown}, newest stable ${latest_level:-unknown}"
if [ -z "$compile_level" ] || [ -z "$target_sdk" ]; then
  echo "::warning::Could not read compileSdk and targetSdk from $BUILD_FILE."
elif [ -z "$latest_level" ] || newer "$compile_level" "$latest_level"; then
  echo "::warning::sdkmanager lists no stable platform at or above compileSdk $compile_level; the SDK package naming may have changed."
elif newer "$latest_level" "$compile_level"; then
  if try_update "Android API" "$latest_level" "$compile_level" set_sdk "$latest_sdk" "${latest_level#*.}" \
    && [ "$latest_sdk" != "$compile_sdk" ]; then
    device_tests+=("Android API $latest_sdk")
  fi
fi

# 4. libxposed. Its api (for the hooks) and service (for the app) share one version, so only a
#    version published for both is used.
xposed_now=$(sed -n 's/^libxposed = "\(.*\)"$/\1/p' "$CATALOG")
xposed_repo=https://repo1.maven.org/maven2/io/github/libxposed
xposed_latest=$(comm -12 <(stable_versions "$xposed_repo/api/maven-metadata.xml") \
  <(stable_versions "$xposed_repo/service/maven-metadata.xml") | newest)
echo "libxposed: using $xposed_now, newest ${xposed_latest:-unknown}"
if [ -z "$xposed_latest" ]; then
  echo "::warning::Could not read the libxposed versions from Maven Central."
elif newer "$xposed_latest" "$xposed_now"; then
  if try_update libxposed "$xposed_latest" "$xposed_now" set_libxposed "$xposed_latest" \
    && [ "${xposed_latest%%.*}" != "${xposed_now%%.*}" ]; then
    device_tests+=("libxposed API ${xposed_latest%%.*}")
  fi
fi

if [ ${#updated[@]} -gt 0 ]; then
  title="build: update to $(join_and "${updated[@]}")"
  git commit -q -m "$title" -m "Automated weekly update check; the debug and release builds pass.

$(printf -- '- %s\n' "${summary[@]}")"
  git pull -q --rebase origin main
  git push -q origin HEAD:main
  echo "Pushed: $title"
fi

if [ ${#device_tests[@]} -gt 0 ]; then
  levels=$(join_and "${device_tests[@]}")
  extra=""
  case " ${device_tests[*]}" in *" Android API"*) extra+=$'\n- [ ] Update "tested on Android …" in the README';; esac
  case " ${device_tests[*]}" in *" libxposed API"*) extra+=$'\n- [ ] Check the libxposed changelog for changes to the hooks the module uses';; esac
  open_issue "Test NoLockQS on $levels" "main now builds with $levels ($(git rev-parse --short HEAD)), but the hooks can only be checked on a phone:

- [ ] Quick Settings can't be pulled down on the lock screen, in portrait and landscape
- [ ] The power menu doesn't open on the lock screen
- [ ] Both work normally once unlocked
- [ ] The module screen shows \"Module active\"$extra

Then bump \`version\` and \`versionCode\` in \`$MODULE_PROP\` to release."
fi

if [ ${#failed[@]} -gt 0 ]; then
  echo "Left out: $(join_and "${failed[@]}")"
  exit 1
fi
if [ ${#updated[@]} -eq 0 ]; then
  echo "Everything is up to date."
fi
