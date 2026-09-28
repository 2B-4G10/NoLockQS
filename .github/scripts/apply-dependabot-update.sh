#!/usr/bin/env bash
# Puts a Dependabot update on main under the repository owner's name and closes its pull request.
# Merging the pull request instead would make dependabot[bot] the author of the commit on main.
# Runs once the pull request's debug and release builds have passed.
#
# Expects: the pull request's merge commit (GITHUB_SHA) checked out, PR_NUMBER and PR_TITLE, gh
# with GH_TOKEN and GH_REPO, GIT_COMMITTER_NAME/EMAIL, and RUNNER_TEMP.
set -euo pipefail

auth=$(printf 'x-access-token:%s' "$GH_TOKEN" | base64 -w0)
remote() { git -c http.extraheader="AUTHORIZATION: basic $auth" "$@"; }

# The update: the pull request's merge commit compared with main as it was when it was built.
remote fetch -q --depth=2 origin "$GITHUB_SHA"
patch="$RUNNER_TEMP/dependabot.patch"
git diff --binary "$GITHUB_SHA^1" "$GITHUB_SHA" > "$patch"

# Dependabot's description of the update, without its metadata block and sign-off.
details=$(git log -1 --format=%B "$GITHUB_SHA^2" | awk 'NR == 1 { next } /^---$/ { exit } !/^Signed-off-by:/')

owner_name=$(gh api "users/$GITHUB_REPOSITORY_OWNER" --jq '.name // empty' || true)
export GIT_AUTHOR_NAME="${owner_name:-$GITHUB_REPOSITORY_OWNER}"
export GIT_AUTHOR_EMAIL="$GITHUB_REPOSITORY_OWNER_ID+$GITHUB_REPOSITORY_OWNER@users.noreply.github.com"

# main can move while this runs, so the update is applied to its newest state, up to three times.
for attempt in 1 2 3; do
  remote fetch -q --depth=1 origin main
  git checkout -q --detach FETCH_HEAD
  if git apply --check --reverse "$patch" 2>/dev/null; then
    result="This update is already on main."
    break
  fi
  if ! git apply --index --3way "$patch"; then
    echo "::error::This update conflicts with main, so it was not applied. Dependabot rebases or closes its pull request once it sees the conflict."
    exit 1
  fi
  git commit -q -m "$PR_TITLE" -m "$details" \
    -m "From Dependabot's pull request #$PR_NUMBER; the debug and release builds pass."
  if remote push -q origin HEAD:main; then
    result="Applied to main in $(git rev-parse HEAD)."
    break
  fi
  if [ "$attempt" -eq 3 ]; then
    echo "::error::Could not push the update to main."
    exit 1
  fi
  echo "main moved; applying the update again."
done

echo "$result"
gh pr close "$PR_NUMBER" --comment "$result" --delete-branch
