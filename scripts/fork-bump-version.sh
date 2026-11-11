#!/usr/bin/env bash
# Fork: bumps the app version in app/build.gradle.kts and prints the new versionName.
#
#   scripts/fork-bump-version.sh minor   # upstream sync: 1.2.0 -> 1.3.0 (ADR 0001)
#   scripts/fork-bump-version.sh patch   # fork-only release: 1.2.0 -> 1.2.1
#
# versionCode always goes up by one, so the installed app accepts the update.
set -euo pipefail

part="${1:-minor}"
gradle_file="${GRADLE_FILE:-app/build.gradle.kts}"

current_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/p' "$gradle_file" | head -n1)"
current_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$gradle_file" | head -n1)"
if [[ -z "$current_name" || -z "$current_code" ]]; then
  echo "Could not read versionName/versionCode from $gradle_file" >&2
  exit 1
fi

IFS=. read -r major minor patch <<<"$current_name"
case "$part" in
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
  *) echo "Usage: $0 [minor|patch]" >&2; exit 2 ;;
esac
new_name="${major}.${minor}.${patch}"
new_code=$((current_code + 1))

sed -i -E \
  -e "s/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)${current_code}\b/\1${new_code}/" \
  -e "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*)\"${current_name}\"/\1\"${new_name}\"/" \
  "$gradle_file"

echo "$new_name"
