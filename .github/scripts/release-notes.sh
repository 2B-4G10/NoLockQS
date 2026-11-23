#!/usr/bin/env bash
# Prints the release notes for version $1, in one of two layouts ($2):
#  github  the release here: the changes, then how to install, and a link to the full diff,
#  xposed  the release in the Xposed Modules Repository: the changes, then a link to the release here.
# The changes are the version's section in CHANGELOG.md. A version without one (for example an
# automated update release) gets a section made from the subjects of its commits, grouped like the
# changelog's, so every release has readable notes.
#
# Optional environment:
#  TAG           the release's tag (default v<version>)
#  PREVIOUS_TAG  the release before it, for the diff link and the commits of a missing section
#  TARGET        the commit the release is made from, when its tag doesn't exist yet
#  APK           the APK's file name (github)
#  SIGNED        "false" adds a warning that the APK is signed with a temporary key (github)
#  RELEASE_URL   the release here (xposed)
# Expects gh with GH_TOKEN, and GITHUB_REPOSITORY and GITHUB_SERVER_URL, for a missing section and
# the links. Runs in the checkout.
set -euo pipefail

version=$1
layout=${2:-github}
tag=${TAG:-v$version}
previous=${PREVIOUS_TAG:-}
target=${TARGET:-$tag}
server=${GITHUB_SERVER_URL:-https://github.com}
repo=${GITHUB_REPOSITORY:-2B-4G10/NoLockQS}

script_dir=$(dirname "$0")
changes=$("$script_dir/changelog.sh" "$version")

# A section made from the commits since the previous release. Release, merge, CI and docs commits
# are left out; "type(scope): " prefixes are dropped and the rest sorted into the usual groups.
generated_changes() {
  local subjects line fixes=() changes=() hood=() group
  [ -n "$previous" ] || return 0
  subjects=$(gh api "repos/$repo/compare/$previous...$target" \
    --jq '.commits[].commit.message | split("\n")[0]' 2>/dev/null) || return 0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$line" in
      "chore: release"* | "Merge "* | ci:* | ci\(* | docs:* | docs\(* | README:* | Changelog:* | "Revert \""*) continue ;;
      build:* | build\(* | refactor:* | refactor\(* | chore:* | chore\(* | deps:* | deps\(*) group=hood ;;
      fix:* | fix\(* | Fix\ *) group=fixes ;;
      *) group=changes ;;
    esac
    # Drop a conventional prefix, name the Gradle plugin, and capitalize.
    line=$(sed -E -e 's/^[a-z]+(\([^)]*\))?!?: //' \
                  -e 's/com\.android\.application/the Android Gradle Plugin/' \
                  -e 's/ in the gradle group$//' <<< "$line")
    line="$(tr '[:lower:]' '[:upper:]' <<< "${line:0:1}")${line:1}"
    case $group in
      fixes) fixes+=("$line") ;;
      changes) changes+=("$line") ;;
      hood) hood+=("$line") ;;
    esac
  done <<< "$subjects"

  if [ ${#fixes[@]} -eq 0 ] && [ ${#changes[@]} -eq 0 ]; then
    [ ${#hood[@]} -gt 0 ] || return 0
    echo "> A maintenance release that keeps NoLockQS on the newest toolchain and libraries."
  else
    echo "> What's new since $previous."
  fi
  if [ ${#changes[@]} -gt 0 ]; then printf '\n### 🔄 Changes\n\n'; printf -- '- %s\n' "${changes[@]}"; fi
  if [ ${#fixes[@]} -gt 0 ]; then printf '\n### 🐞 Fixes\n\n'; printf -- '- %s\n' "${fixes[@]}"; fi
  if [ ${#hood[@]} -gt 0 ]; then printf '\n### 🧹 Under the hood\n\n'; printf -- '- %s\n' "${hood[@]}"; fi
}

if [ -z "$changes" ]; then changes=$(generated_changes); fi

case $layout in
  github)
    if [ -n "$changes" ]; then printf '%s\n\n' "$changes"; fi
    if [ "${SIGNED:-true}" = false ]; then
      printf '> [!WARNING]\n> Signed with a temporary key: uninstall the previous version before installing this one.\n\n'
    fi
    echo "---"
    echo
    echo "### 📦 Install"
    echo
    if [ -n "${APK:-}" ]; then
      echo "1. Download **\`$APK\`** below and install it on your rooted device."
    else
      echo "1. Install the NoLockQS APK on your rooted device."
    fi
    echo "2. In Vector, enable NoLockQS for **System UI** and **System Framework**."
    echo "3. Reboot, and the protection is active."
    if [ -n "$previous" ]; then
      printf '\n**Full changelog:** [%s...%s](%s/%s/compare/%s...%s)\n' \
        "$previous" "$tag" "$server" "$repo" "$previous" "$tag"
    fi
    ;;
  xposed)
    if [ -n "$changes" ]; then printf '%s\n\n' "$changes"; fi
    if [ -n "${RELEASE_URL:-}" ]; then
      echo "---"
      echo
      echo "📄 Full release notes and source: [NoLockQS $tag]($RELEASE_URL)"
    fi
    ;;
  *)
    echo "Unknown layout: $layout (use github or xposed)" >&2
    exit 1
    ;;
esac
