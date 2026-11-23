#!/usr/bin/env bash
# Prints the section for version $1 in CHANGELOG.md: everything under its "## v<version>" heading
# (which may carry a date, as in "## v2.8 · 2026-11-08"), up to the next "## " heading, with the
# blank lines around it trimmed. Prints nothing if there is no such section.
set -euo pipefail

version=$(sed 's/[.[\*^$]/\\&/g' <<< "$1")
awk -v v="$version" '
  tolower($0) ~ "^## v" v "([ :]|$)" { found = 1; next }
  found && /^## / { exit }
  found {
    if ($0 ~ /^[[:space:]]*$/) { if (started) blank = 1; next }
    if (blank) print ""
    blank = 0; started = 1
    print
  }
' CHANGELOG.md
