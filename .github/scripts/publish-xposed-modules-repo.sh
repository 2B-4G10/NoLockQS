#!/usr/bin/env bash
# Publishes the current version to the module's repository in the Xposed Modules Repository
# (modules.lsposed.org). Vector's Store shows that site's list from a copy, backup.modules.lsposed.org,
# which runs about three days behind it. The repository is named after the module's package, and
# the site lists it only while it has a description and a release that:
#  - is tagged "<versionCode>-<versionName>" (for example 8-1.9); any other tag is ignored,
#  - is published, not a draft,
#  - has an asset of type application/vnd.android.package-archive: the APK.
# The organization's bot re-tags a release from the APK it had when it was published (so a matching
# tag is left alone), and turns it into a draft if the APK is not a valid Xposed module. It never
# sees an APK swapped in later, which is why the release is created with its APK already attached.
#
# This script:
#  1. makes the repository's files match this branch: its README.md (with relative links pointing
#     back here), a SOURCE_URL to this repository, FUNDING.yml and the files in
#     .github/xposed-modules-repo (SUMMARY). Nothing else: the rules ask for no source code there.
#  2. sets the repository's description to the module name and its homepage to this repository.
#  3. unless it is already there, releases the version in module.prop with the APK of this
#     repository's "v<version>" release, once the APK passes the bot's checks and its package and
#     version match the repository name and module.prop.
#
# Expects: MODULE_REPO (owner/name), XPOSED_TOKEN (a token that can push to it and publish releases;
# without one, nothing is published), APK (the release's APK file name, used if this run released it
# and otherwise downloaded from the release), GIT_COMMITTER_NAME/EMAIL, gh with GH_TOKEN and GH_REPO
# (this repository), ANDROID_HOME with build-tools (for aapt2), and RUNNER_TEMP. Runs in the checkout.
set -euo pipefail

MODULE_PROP=app/src/main/resources/META-INF/xposed/module.prop
EXTRA_FILES=.github/xposed-modules-repo
APK_TYPE=application/vnd.android.package-archive

if [ -z "${XPOSED_TOKEN:-}" ]; then
  echo "::warning::$MODULE_REPO was not updated: add the XPOSED_MODULES_REPO_TOKEN secret, a token that can push to it (see .github/workflows/android.yml), then run this workflow again."
  exit 0
fi

prop() { sed -n "s/^$1=//p" "$MODULE_PROP" | tr -d '[:space:]'; }
name=$(sed -n 's/^name=//p' "$MODULE_PROP" | sed 's/[[:space:]]*$//')
version=$(prop version)
version_code=$(prop versionCode)
tag="$version_code-$version"
package=${MODULE_REPO#*/}
source_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY"

# gh and git as the owner of the token, for the module's repository.
module_gh() { GH_TOKEN="$XPOSED_TOKEN" gh "$@"; }
auth=$(printf 'x-access-token:%s' "$XPOSED_TOKEN" | base64 -w0)
remote() { git -c http.extraheader="AUTHORIZATION: basic $auth" "$@"; }

owner_name=$(gh api "users/$GITHUB_REPOSITORY_OWNER" --jq '.name // empty' || true)
export GIT_AUTHOR_NAME="${owner_name:-$GITHUB_REPOSITORY_OWNER}"
export GIT_AUTHOR_EMAIL="$GITHUB_REPOSITORY_OWNER_ID+$GITHUB_REPOSITORY_OWNER@users.noreply.github.com"

# 1. The repository's files.
repo_dir="$RUNNER_TEMP/module-repo"
rm -rf "$repo_dir"
remote clone -q --depth 1 "$GITHUB_SERVER_URL/$MODULE_REPO.git" "$repo_dir"
branch=$(git -C "$repo_dir" rev-parse --abbrev-ref HEAD)
git -C "$repo_dir" rm -rq --ignore-unmatch .

# Relative links in README.md point at this branch here (images at the raw file, links at its page),
# so the copy only changes when README.md does.
raw="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/$GITHUB_REF_NAME"
blob="$source_url/blob/$GITHUB_REF_NAME"
sed -E \
  -e 's|src="([^":#/][^":]*)"|src="'"$raw"'/\1"|g' \
  -e 's|href="([^":#/][^":]*)"|href="'"$blob"'/\1"|g' \
  -e 's|(!\[[^]]*\])\(([^):#/ ][^): ]*)\)|\1('"$raw"'/\2)|g' \
  -e 's|\]\(([^):#/ ][^): ]*)\)|]('"$blob"'/\1)|g' \
  README.md > "$repo_dir/README.md"
printf '%s\n' "$source_url" > "$repo_dir/SOURCE_URL"
if [ -f .github/FUNDING.yml ]; then
  mkdir -p "$repo_dir/.github"
  cp .github/FUNDING.yml "$repo_dir/.github/"
fi
cp -R "$EXTRA_FILES/." "$repo_dir/"

git -C "$repo_dir" add -A
if git -C "$repo_dir" diff --cached --quiet; then
  echo "$MODULE_REPO: the files are up to date."
else
  git -C "$repo_dir" commit -q -m "Update from $GITHUB_REPOSITORY@${GITHUB_SHA:0:7}" \
    -m "The module's description, published from $source_url/commit/$GITHUB_SHA."
  remote -C "$repo_dir" push -q origin "HEAD:$branch"
  echo "$MODULE_REPO: updated the files on $branch."
fi

# 2. The repository's description and homepage.
settings=$(module_gh repo view "$MODULE_REPO" --json description,homepageUrl --jq '.description + "|" + .homepageUrl' || true)
if [ "$settings" != "$name|$source_url" ]; then
  if module_gh repo edit "$MODULE_REPO" --description "$name" --homepage "$source_url" > /dev/null; then
    echo "$MODULE_REPO: set the description to $name and the homepage to $source_url."
  else
    echo "::warning::Could not set the description and homepage of $MODULE_REPO, which needs admin access to it. Set them by hand: $name and $source_url."
  fi
fi

# 3. The release.
if module_gh api "repos/$MODULE_REPO/releases/tags/$tag" --silent 2>/dev/null; then
  echo "$MODULE_REPO: $tag is already released."
  exit 0
fi

apk=$APK
if [ ! -f "$apk" ] && ! gh release download "v$version" --pattern '*.apk' --output "$apk"; then
  echo "::error::Found no APK to publish: the release v$version here has none."
  exit 1
fi

# The APK's package and version, read from its manifest as the bot does, and its Xposed metadata.
aapt2=$(find "$ANDROID_HOME/build-tools" -name aapt2 -type f 2>/dev/null | sort -V | tail -1)
if [ -z "$aapt2" ]; then
  echo "::error::aapt2 was not found under $ANDROID_HOME/build-tools, so the APK can't be checked."
  exit 1
fi
badging=$("$aapt2" dump badging "$apk" | sed -n '/^package: /p')
apk_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
apk_code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<< "$badging")
apk_version=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")
entries=$(unzip -Z1 "$apk")
module_prop=$(unzip -p "$apk" META-INF/xposed/module.prop 2>/dev/null || true)
echo "$apk: package $apk_package, versionCode $apk_code, versionName $apk_version"

if [ "$apk_package" != "$package" ]; then
  echo "::error::$apk has the package $apk_package, but the Xposed Modules Repository lists this module as $package, so Vector could not tell they are the same module. Set applicationId to $package in app/build.gradle.kts and release a new version."
  exit 1
fi
if [ "$apk_code-$apk_version" != "$tag" ]; then
  echo "::error::$apk is version $apk_version ($apk_code), but module.prop says $version ($version_code)."
  exit 1
fi
if ! grep -Eqx 'META-INF/xposed/(java|native)_init\.list' <<< "$entries" \
  || ! grep -q 'minApiVersion=' <<< "$module_prop" || ! grep -q 'targetApiVersion=' <<< "$module_prop"; then
  echo "::error::$apk is missing the libxposed metadata (META-INF/xposed/module.prop with minApiVersion and targetApiVersion, and java_init.list), so the bot would reject it."
  exit 1
fi

# A draft for this tag is what a failed run leaves, or what the bot makes of a release it rejects.
module_gh api "repos/$MODULE_REPO/releases?per_page=100" \
  --jq ".[] | select(.draft and .tag_name == \"$tag\") | .id" \
  | while read -r id; do module_gh api -X DELETE "repos/$MODULE_REPO/releases/$id"; done

changes=$(.github/scripts/changelog.sh "$version")
notes="$RUNNER_TEMP/module-release-notes.md"
{
  if [ -n "$changes" ]; then printf '%s\n\n' "$changes"; fi
  echo "Full release notes: $source_url/releases/tag/v$version"
} > "$notes"

# Created as a draft, given its APK (with the type the site looks for), then published, so the
# bot and the site never see the release without it.
release_id=$(module_gh api -X POST "repos/$MODULE_REPO/releases" -f tag_name="$tag" \
  -f target_commitish="$branch" -f name="$version" -F body=@"$notes" -F draft=true --jq .id)
curl -fsS -o /dev/null -X POST -H "Authorization: Bearer $XPOSED_TOKEN" -H "Content-Type: $APK_TYPE" \
  --data-binary @"$apk" "https://uploads.github.com/repos/$MODULE_REPO/releases/$release_id/assets?name=$apk"
url=$(module_gh api -X PATCH "repos/$MODULE_REPO/releases/$release_id" -F draft=false -f make_latest=true --jq .html_url)

echo "$MODULE_REPO: released $tag at $url"
echo "modules.lsposed.org lists it within about 15 minutes, and Vector's Store about three days later."
echo "The bot's check shows up at https://github.com/Xposed-Modules-Repo/modules/actions/workflows/tag.yml"
