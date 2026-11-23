#!/usr/bin/env bash
# Rewrites the notes of every published release, here and in the Xposed Modules Repository, from
# CHANGELOG.md through release-notes.sh, so an edit to the changelog shows up on releases already
# out. Only notes that change are written; releases, tags and APKs are left alone, and a release
# whose version has no section in the changelog keeps its notes.
#
# Expects: gh with GH_TOKEN (allowed to edit this repository's releases), GITHUB_REPOSITORY,
# optionally MODULE_REPO and XPOSED_TOKEN for the Xposed Modules Repository (skipped without the
# token), jq and RUNNER_TEMP. Runs in the checkout.
set -euo pipefail

notes_script=.github/scripts/release-notes.sh
work=${RUNNER_TEMP:-$(mktemp -d)}/release-notes
mkdir -p "$work"
urls="$work/urls.tsv"   # version, release URL here
: > "$urls"

# True when the body $2 differs from the file $1, ignoring line endings and trailing blank space.
differs() {
  ! cmp -s <(tr -d '\r' < "$1" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}') \
            <(printf '%s\n' "$2" | tr -d '\r' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
}

# 1. This repository's releases, oldest first, each linked to the one before it.
updated=0
previous=""
while IFS= read -r release; do
  tag=$(jq -r .tag_name <<< "$release")
  version=${tag#[vV]}
  url=$(jq -r .html_url <<< "$release")
  printf '%s\t%s\t%s\n' "$version" "$url" "$tag" >> "$urls"
  if [ -n "$(.github/scripts/changelog.sh "$version")" ]; then
    body=$(jq -r '.body // ""' <<< "$release")
    signed=true
    if grep -q 'temporary key' <<< "$body"; then signed=false; fi
    apk=$(jq -r '[.assets[].name | select(endswith(".apk"))] | (map(select(test("debug"; "i") | not)) + .)[0] // empty' <<< "$release")
    TAG="$tag" PREVIOUS_TAG="$previous" APK="$apk" SIGNED="$signed" \
      "$notes_script" "$version" github > "$work/notes.md"
    if differs "$work/notes.md" "$body"; then
      gh api -X PATCH "repos/$GITHUB_REPOSITORY/releases/$(jq -r .id <<< "$release")" \
        -F body=@"$work/notes.md" --silent
      echo "$tag: notes updated ($url)"
      updated=$((updated + 1))
    else
      echo "$tag: notes already up to date"
    fi
  else
    echo "$tag: no section for $version in CHANGELOG.md, notes left as they are"
  fi
  previous=$tag
done < <(gh api "repos/$GITHUB_REPOSITORY/releases?per_page=100" \
  --jq '[.[] | select(.draft | not)] | sort_by(.published_at) | .[] | @json')
echo "$GITHUB_REPOSITORY: $updated release notes updated."

# 2. The Xposed Modules Repository's releases, tagged "<versionCode>-<versionName>" (the oldest
#    ones "<versionName>-NoLockQS"), each with a link to the release here.
if [ -z "${MODULE_REPO:-}" ] || [ -z "${XPOSED_TOKEN:-}" ]; then
  echo "::warning::No XPOSED_MODULES_REPO_TOKEN secret: the Xposed Modules Repository's release notes were not updated."
  exit 0
fi
module_gh() { GH_TOKEN="$XPOSED_TOKEN" gh "$@"; }
updated=0
while IFS= read -r release; do
  tag=$(jq -r .tag_name <<< "$release")
  if [[ $tag =~ ^[0-9]+-(.+)$ ]]; then
    version=${BASH_REMATCH[1]}
  elif [[ $tag =~ ^[vV]?(.+)-NoLockQS$ ]]; then
    version=${BASH_REMATCH[1]}
  else
    echo "$MODULE_REPO $tag: not a release tag this script knows, left as it is"
    continue
  fi
  if [ -z "$(.github/scripts/changelog.sh "$version")" ]; then
    echo "$MODULE_REPO $tag: no section for $version in CHANGELOG.md, notes left as they are"
    continue
  fi
  source_url=$(awk -F '\t' -v v="$version" '$1 == v { print $2; exit }' "$urls")
  source_tag=$(awk -F '\t' -v v="$version" '$1 == v { print $3; exit }' "$urls")
  TAG="${source_tag:-v$version}" RELEASE_URL="$source_url" \
    "$notes_script" "$version" xposed > "$work/notes.md"
  if differs "$work/notes.md" "$(jq -r '.body // ""' <<< "$release")"; then
    module_gh api -X PATCH "repos/$MODULE_REPO/releases/$(jq -r .id <<< "$release")" \
      -F body=@"$work/notes.md" --silent
    echo "$MODULE_REPO $tag: notes updated"
    updated=$((updated + 1))
  else
    echo "$MODULE_REPO $tag: notes already up to date"
  fi
done < <(module_gh api "repos/$MODULE_REPO/releases?per_page=100" \
  --jq '[.[] | select(.draft | not)] | sort_by(.published_at) | .[] | @json')
echo "$MODULE_REPO: $updated release notes updated."
