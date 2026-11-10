#!/bin/bash
# Runs Android Gradle checks only when Android sources, SDKs, or build inputs changed.

set -euo pipefail

if (($# == 0)); then
  printf 'Usage: %s --needed | gradle-task [gradle-argument ...]\n' "$0" >&2
  exit 2
fi
if [[ "$1" == --needed && $# != 1 ]]; then
  printf '%s\n' '--needed does not accept additional arguments.' >&2
  exit 2
fi

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly script_dir
repository_dir="$(CDPATH='' cd -- "$script_dir/.." && pwd)"
readonly repository_dir
cd -- "$repository_dir"

# Without a usable base, run all checks. Include branch commits, staged/unstaged
# edits, deletions, renames, and untracked files when comparing locally.
base="${ANDROID_CHECK_BASE:-}"
if [[ -z "$base" && "${CI:-false}" != true ]]; then
  if upstream="$(git rev-parse --verify '@{upstream}' 2>/dev/null)" && ancestor="$(git merge-base HEAD "$upstream")"; then
    base="$ancestor"
  fi
fi
needed=true
if [[ -n "$base" ]] && git rev-parse --verify "$base^{commit}" >/dev/null 2>&1; then
  pathspecs="$(uv run --locked python scripts/android_paths.py)"
  mapfile -t paths <<<"$pathspecs"
  if git diff --quiet "$base" -- "${paths[@]}"; then
    untracked="$(git ls-files --others --exclude-standard -- "${paths[@]}")"
    if [[ -z "$untracked" ]]; then
      needed=false
    fi
  else
    status=$?
    if ((status != 1)); then
      exit "$status"
    fi
  fi
fi

if [[ "$1" == --needed ]]; then
  printf '%s\n' "$needed"
elif [[ "$needed" == false ]]; then
  printf '%s\n' 'Skipping Android checks: no Android, SDK, or build input changes.'
else
  uv run --locked python scripts/android_sdk.py check
  cd -- ./android
  exec ./gradlew "$@"
fi
