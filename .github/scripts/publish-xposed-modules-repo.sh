#!/usr/bin/env bash
# Publishes NoLockQS to its repository in the Xposed Modules Repository (modules.lsposed.org). The
# site rebuilds once the organization's GitHub activity pauses for two minutes, at most every 15
# minutes, so a release can take hours to show up there. Vector's Store shows the site's list from a
# copy, backup.modules.lsposed.org, which runs about three days behind.
# The repository is named after the module's package, and the site lists it only while it has a
# description and a release that:
#  - is tagged "<versionCode>-<versionName>" (for example 8-1.9); any other tag is ignored,
#  - is published, not a draft,
#  - has an asset of type application/vnd.android.package-archive: the APK.
# The organization's bot re-tags a release from the APK it had when it was published (so a matching
# tag is left alone), and turns it into a draft if the APK is not a valid Xposed module. It never
# sees an APK swapped in later, which is why the release is created with its APK already attached.
#
# This script:
#  1. checks that the token still works, and warns two weeks before it expires.
#  2. makes the repository's files match this branch: its README.md (with relative links pointing
#     back here), a SCOPE from scope.list, a SOURCE_URL to this repository, FUNDING.yml and the
#     files in .github/xposed-modules-repo (SUMMARY). Nothing else: the rules ask for no source code
#     there.
#  3. sets the repository's description to the module name and its homepage to this repository.
#  4. publishes a release of this repository there, unless it is already there: RELEASE_TAG, or
#     else the latest release. Its APK must pass the bot's checks, have the repository's package
#     and be signed with the same key as the newest APK there, so that Vector can update to it. The
#     release there is tagged from the APK's own version, like the bot does.
#
# Expects: MODULE_REPO (owner/name), XPOSED_TOKEN (a token that can push to it and publish releases;
# without one, nothing is published), SOURCE_BRANCH (the branch checked out here, which README.md
# links point at), optionally RELEASE_TAG and APK (that release's APK file, when this run made it;
# otherwise it is downloaded), GIT_COMMITTER_NAME/EMAIL, gh with GH_TOKEN and GH_REPO (this
# repository), ANDROID_HOME with build-tools (aapt2 and apksigner), Java (JAVA_HOME, else on the
# PATH) and RUNNER_TEMP. Runs in the checkout.
set -euo pipefail

MODULE_PROP=app/src/main/resources/META-INF/xposed/module.prop
SCOPE_LIST=app/src/main/resources/META-INF/xposed/scope.list
EXTRA_FILES=.github/xposed-modules-repo
APK_TYPE=application/vnd.android.package-archive
TOKEN_HELP="make a new token as described in .github/workflows/android.yml and save it as the XPOSED_MODULES_REPO_TOKEN secret"

if [ -z "${XPOSED_TOKEN:-}" ]; then
  echo "::warning::$MODULE_REPO was not updated: add the XPOSED_MODULES_REPO_TOKEN secret, a token that can push to it (see .github/workflows/android.yml), then run this workflow again."
  exit 0
fi

prop() { sed -n "s/^$1=//p" "$MODULE_PROP" | tr -d '[:space:]'; }
name=$(sed -n 's/^name=//p' "$MODULE_PROP" | sed 's/[[:space:]]*$//')
version=$(prop version)
version_code=$(prop versionCode)
package=${MODULE_REPO#*/}
source_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY"
source_branch=${SOURCE_BRANCH:-$GITHUB_REF_NAME}
source_sha=$(git rev-parse HEAD)

# gh and git as the owner of the token, for the module's repository.
module_gh() { GH_TOKEN="$XPOSED_TOKEN" gh "$@"; }
auth=$(printf 'x-access-token:%s' "$XPOSED_TOKEN" | base64 -w0)
remote() { git -c http.extraheader="AUTHORIZATION: basic $auth" "$@"; }

# 1. The token. GitHub refuses it once it has expired or been revoked, and gives a classic token's
#    scopes and expiry date in the response headers.
if ! token_info=$(module_gh api --include user 2>&1); then
  echo "::error::GitHub refused the XPOSED_MODULES_REPO_TOKEN secret, so $MODULE_REPO can't be updated. It has probably expired: $TOKEN_HELP, then run this workflow again."
  exit 1
fi
header() { sed -n "s/^$1: *//Ip" <<< "$token_info" | tr -d '\r'; }
scopes=$(header x-oauth-scopes)
expires=$(header github-authentication-token-expiration)
if grep -qi '^x-oauth-scopes:' <<< "$token_info" && ! grep -Eq '(^|[ ,])(public_repo|repo)(,|$)' <<< "$scopes"; then
  echo "::error::The XPOSED_MODULES_REPO_TOKEN secret can't push to $MODULE_REPO, as it lacks the public_repo scope: $TOKEN_HELP."
  exit 1
fi
echo "The XPOSED_MODULES_REPO_TOKEN secret works (expires: ${expires:-never})."
if [ -n "$scopes" ] && [ "$scopes" != public_repo ]; then
  echo "::warning::The XPOSED_MODULES_REPO_TOKEN secret has more access than publishing needs, which is only the public_repo scope. A token with just that scope is safer, as a leak could then do no more than this: $TOKEN_HELP, then revoke the old one."
fi
if [ -n "$expires" ] && expires_at=$(date -d "$expires" +%s 2>/dev/null); then
  days_left=$(( (expires_at - $(date +%s)) / 86400 ))
  if [ "$days_left" -lt 14 ]; then
    echo "::warning::The XPOSED_MODULES_REPO_TOKEN secret expires in $days_left days ($expires), and then $MODULE_REPO stops being updated: $TOKEN_HELP."
  fi
fi

owner_name=$(gh api "users/$GITHUB_REPOSITORY_OWNER" --jq '.name // empty' || true)
export GIT_AUTHOR_NAME="${owner_name:-$GITHUB_REPOSITORY_OWNER}"
export GIT_AUTHOR_EMAIL="$GITHUB_REPOSITORY_OWNER_ID+$GITHUB_REPOSITORY_OWNER@users.noreply.github.com"

# 2. The repository's files.
repo_dir="$RUNNER_TEMP/module-repo"
rm -rf "$repo_dir"
remote clone -q --depth 1 "$GITHUB_SERVER_URL/$MODULE_REPO.git" "$repo_dir"
branch=$(git -C "$repo_dir" rev-parse --abbrev-ref HEAD)
git -C "$repo_dir" rm -rq --ignore-unmatch .

# Relative links in README.md point at this branch here (images at the raw file, links at its page),
# so the copy only changes when README.md does.
raw="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/$source_branch"
blob="$source_url/blob/$source_branch"
sed -E \
  -e 's|src="([^":#/][^":]*)"|src="'"$raw"'/\1"|g' \
  -e 's|href="([^":#/][^":]*)"|href="'"$blob"'/\1"|g' \
  -e 's|(!\[[^]]*\])\(([^):#/ ][^): ]*)\)|\1('"$raw"'/\2)|g' \
  -e 's|\]\(([^):#/ ][^): ]*)\)|]('"$blob"'/\1)|g' \
  README.md > "$repo_dir/README.md"
# The apps it hooks, which Vector's Store shows before installing, as a JSON list in scope.list's
# words ("system" is System Framework). The module's own package is left out: it only hooks itself
# to show that it is active.
sed 's/[[:space:]]//g' "$SCOPE_LIST" \
  | jq -R --arg self "$package" 'select(length > 0 and (startswith("#") | not) and . != $self)' \
  | jq -s . > "$repo_dir/SCOPE"
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
  git -C "$repo_dir" commit -q -m "Update from $GITHUB_REPOSITORY@${source_sha:0:7}" \
    -m "The module's description, published from $source_url/commit/$source_sha."
  remote -C "$repo_dir" push -q origin "HEAD:$branch"
  echo "$MODULE_REPO: updated the files on $branch."
fi

# 3. The repository's description and homepage.
settings=$(module_gh repo view "$MODULE_REPO" --json description,homepageUrl --jq '.description + "|" + .homepageUrl' || true)
if [ "$settings" != "$name|$source_url" ]; then
  if module_gh repo edit "$MODULE_REPO" --description "$name" --homepage "$source_url" > /dev/null; then
    echo "$MODULE_REPO: set the description to $name and the homepage to $source_url."
  else
    echo "::warning::Could not set the description and homepage of $MODULE_REPO, which needs admin access to it. Set them by hand: $name and $source_url."
  fi
fi

# 4. The release.
module_has() { module_gh api "repos/$MODULE_REPO/releases/tags/$1" --silent 2>/dev/null; }

release_tag=${RELEASE_TAG:-}
if [ -z "$release_tag" ] && ! release_tag=$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name 2>/dev/null); then
  echo "$GITHUB_REPOSITORY has no release yet, so there is nothing to publish."
  exit 0
fi
# Android CI releases module.prop's version as v<version>: its tag there is known without the APK.
if [ "$release_tag" = "v$version" ] && module_has "$version_code-$version"; then
  echo "$MODULE_REPO: $version_code-$version, from $release_tag, is already released."
  exit 0
fi
if ! release=$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/$release_tag" 2>/dev/null); then
  echo "::error::$GITHUB_REPOSITORY has no published release $release_tag to publish."
  exit 1
fi

apk=${APK:-}
if [ -z "$apk" ] || [ ! -f "$apk" ]; then
  # The release's APK, preferring one that isn't a debug build.
  asset=$(jq -r '[.assets[].name | select(endswith(".apk"))] | (map(select(test("debug"; "i") | not)) + .)[0] // empty' <<< "$release")
  if [ -z "$asset" ]; then
    echo "::error::The release $release_tag has no APK to publish to $MODULE_REPO."
    exit 1
  fi
  apk="$RUNNER_TEMP/release-apk/$asset"
  mkdir -p "${apk%/*}"
  gh release download "$release_tag" --pattern "$asset" --output "$apk"
fi

build_tool() { find "$ANDROID_HOME/build-tools" -name "$1" -type f 2>/dev/null | sort -V | tail -1; }
aapt2=$(build_tool aapt2)
apksigner=$(build_tool apksigner)
if [ -z "$aapt2" ] || [ -z "$apksigner" ]; then
  echo "::error::aapt2 and apksigner were not found under $ANDROID_HOME/build-tools, so the APK can't be checked."
  exit 1
fi

# The APK's package and version, read from its manifest as the bot does, and its Xposed metadata.
badging=$("$aapt2" dump badging "$apk" | sed -n '/^package: /p')
apk_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
apk_code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<< "$badging")
apk_version=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")
entries=$(unzip -Z1 "$apk")
module_prop=$(unzip -p "$apk" META-INF/xposed/module.prop 2>/dev/null || true)
tag="$apk_code-$apk_version"
echo "${apk##*/} (from $release_tag): package $apk_package, versionCode $apk_code, versionName $apk_version"

if [ "$apk_package" != "$package" ]; then
  echo "::error::${apk##*/} has the package $apk_package, but the Xposed Modules Repository lists this module as $package, so Vector could not tell they are the same module. Set applicationId to $package in app/build.gradle.kts and release a new version."
  exit 1
fi
if [ "$release_tag" = "v$version" ] && [ "$tag" != "$version_code-$version" ]; then
  echo "::error::${apk##*/} is version $apk_version ($apk_code), but module.prop says $version ($version_code)."
  exit 1
fi
if module_has "$tag"; then
  echo "$MODULE_REPO: $tag, from $release_tag, is already released."
  exit 0
fi
if ! grep -Eqx 'META-INF/xposed/(java|native)_init\.list' <<< "$entries" \
  || ! grep -q 'minApiVersion=' <<< "$module_prop" || ! grep -q 'targetApiVersion=' <<< "$module_prop"; then
  echo "::error::${apk##*/} is missing the libxposed metadata (META-INF/xposed/module.prop with minApiVersion and targetApiVersion, and java_init.list), so the bot would reject it."
  exit 1
fi

# Vector only updates an app with an APK signed by the same key, so a release signed with another
# one (a debug build, say) would strand everyone who installed it from there.
signer() {
  PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH" "$apksigner" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
if ! apk_signer=$(signer "$apk") || [ -z "$apk_signer" ]; then
  echo "::error::${apk##*/} is not validly signed, so Vector could not install it."
  exit 1
fi
newest=$(module_gh api "repos/$MODULE_REPO/releases?per_page=100" --jq '
  [.[] | select((.draft | not) and (.tag_name | test("^[0-9]+-")))
       | {tag: .tag_name, published_at, url: ([.assets[] | select(.name | endswith(".apk")) | .browser_download_url][0])}
       | select(.url)]
  | sort_by(.published_at) | last // empty | "\(.tag) \(.url)"')
if [ -n "$newest" ]; then
  curl -fsSL -o "$RUNNER_TEMP/newest-module.apk" "${newest#* }"
  if [ "$(signer "$RUNNER_TEMP/newest-module.apk" || true)" != "$apk_signer" ]; then
    echo "::error::${apk##*/} is signed with a different key than the APK of ${newest%% *} in $MODULE_REPO, so Vector could not update to it from there. Publish an APK signed with the release key, like the ones Android CI builds."
    exit 1
  fi
fi

# A draft for this tag is what a failed run leaves, or what the bot makes of a release it rejects.
module_gh api "repos/$MODULE_REPO/releases?per_page=100" \
  --jq ".[] | select(.draft and .tag_name == \"$tag\") | .id" \
  | while read -r id; do module_gh api -X DELETE "repos/$MODULE_REPO/releases/$id"; done

# The changes listed in CHANGELOG.md, or else the release's own notes.
changes=$(.github/scripts/changelog.sh "$apk_version")
if [ -z "$changes" ]; then changes=$(jq -r '.body // empty' <<< "$release"); fi
notes="$RUNNER_TEMP/module-release-notes.md"
{
  if [ -n "$changes" ]; then printf '%s\n\n' "$changes"; fi
  echo "Full release notes: $(jq -r .html_url <<< "$release")"
} > "$notes"

# A pre-release stays one there, and doesn't become the latest release.
prerelease=$(jq -r '.prerelease == true' <<< "$release")
latest=true
if [ "$prerelease" = true ]; then latest=false; fi
asset_name="$name-v$apk_version.apk"

# Created as a draft, given its APK (with the type the site looks for), then published, so the
# bot and the site never see the release without it.
release_id=$(module_gh api -X POST "repos/$MODULE_REPO/releases" -f tag_name="$tag" \
  -f target_commitish="$branch" -f name="$apk_version" -F body=@"$notes" -F draft=true \
  -F prerelease="$prerelease" --jq .id)
curl -fsS -o /dev/null -X POST -H "Authorization: Bearer $XPOSED_TOKEN" -H "Content-Type: $APK_TYPE" \
  --data-binary @"$apk" \
  "https://uploads.github.com/repos/$MODULE_REPO/releases/$release_id/assets?name=$(jq -rn --arg n "$asset_name" '$n | @uri')"
url=$(module_gh api -X PATCH "repos/$MODULE_REPO/releases/$release_id" -F draft=false -f make_latest="$latest" --jq .html_url)

echo "$MODULE_REPO: released $tag at $url"
echo "modules.lsposed.org lists it at its next rebuild, which can take a few hours, and Vector's Store about three days after that."
echo "The bot's check shows up at https://github.com/Xposed-Modules-Repo/modules/actions/workflows/tag.yml"
