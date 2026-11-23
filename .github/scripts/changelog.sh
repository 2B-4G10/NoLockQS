#!/usr/bin/env bash
# Prints the section for version $1 in CHANGELOG.md: everything under its "## v<version>" heading
# (which may carry a date, as in "## v2.8 · 2026-11-08"), up to the next "## " heading, with the
# blank lines around it trimmed. Prints nothing if there is no such section.
set -euo pipefail

# The heading is matched as plain text, so the dots in a version are not regex wildcards.
awk -v head="## v$1" '
  function is_heading(line,   rest) {
    if (tolower(substr(line, 1, length(head))) != tolower(head)) return 0
    rest = substr(line, length(head) + 1, 1)
    return rest == "" || rest == " " || rest == ":"
  }
  !found && is_heading($0) { found = 1; next }
  found && /^## / { exit }
  found {
    if ($0 ~ /^[[:space:]]*$/) { if (started) blank = 1; next }
    if (blank) print ""
    blank = 0; started = 1
    print
  }
' CHANGELOG.md
