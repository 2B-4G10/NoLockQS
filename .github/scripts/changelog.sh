#!/usr/bin/env bash
# Prints the changes listed for version $1 in CHANGELOG.md: its "## v<version>" section, up to the
# next "## " heading, without blank lines. Prints nothing if there is no such section.
set -euo pipefail

awk -v v="$1" 'tolower($0) ~ "^## v" v "([ :]|$)" { found = 1; next } found && /^## / { exit } found' CHANGELOG.md \
  | sed '/^[[:space:]]*$/d'
