#!/usr/bin/env bash
# Conservative, reversible cleanup for the rllib4prob/fresh tree.
set -euo pipefail

usage() {
  cat <<'HELP'
Usage:
  bash scripts/maintenance/cleanup.sh --dry-run
  bash scripts/maintenance/cleanup.sh --apply

Moves only archived IVI experiments, Java *.bak backups and loose root log
files into .local-archive/DATE/. It never deletes anything and never touches
src/main/resources, experiments/ or active scripts.
HELP
}

mode="${1:---dry-run}"
case "$mode" in
  --dry-run|--apply) ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
if [[ ! -f "$root/pom.xml" || ! -d "$root/src/main/java" ]]; then
  echo "ERROR: invalid project root: $root" >&2
  exit 1
fi

stamp="$(date +%Y%m%d-%H%M%S)"
archive="$root/.local-archive/$stamp"
count=0

move_one() {
  local src="$1"
  [[ -e "$src" ]] || return 0
  [[ ! -L "$src" ]] || { printf 'SKIP symbolic link: %s\n' "$src"; return 0; }
  local relative="${src#"$root/"}"
  local dest="$archive/$relative"
  count=$((count+1))
  if [[ "$mode" == "--dry-run" ]]; then
    printf '[DRY RUN] %s -> .local-archive/%s/%s\n' "$relative" "$stamp" "$relative"
  else
    mkdir -p "$(dirname "$dest")"
    if [[ -e "$dest" ]]; then
      echo "ERROR: destination already exists: $dest" >&2
      exit 1
    fi
    mv "$src" "$dest"
    printf '[MOVED] %s\n' "$relative"
  fi
}

# Legacy temporary IVI package and one-time manually created Java backups.
move_one "$root/IVI_facile"
for src in "$root"/backup-once-tictactoe-*; do move_one "$src"; done

# Only known source/test backup extensions, not legitimate source files.
for src in \
  "$root"/src/main/java/fr/polytech/mnia/Environment/*.bak \
  "$root"/src/main/java/fr/polytech/mnia/mbrl/offline/*.bak* \
  "$root"/src/test/java/fr/polytech/mnia/Environment/*.bak; do
  move_one "$src"
done

# Loose manual logs in project root. The full documented experimental
# campaigns and their logs inside experiments/ are deliberately untouched.
for src in "$root"/*.log "$root"/*.logclear; do move_one "$src"; done

# One-off inventory generated for this audit, not a publication artifact.
move_one "$root/fresh_inventory.txt"

if [[ "$mode" == "--dry-run" ]]; then
  printf '\nDRY RUN: %d move(s) suggested; nothing changed.\n' "$count"
else
  printf '\nAPPLIED: %d move(s). Archive: %s\n' "$count" "$archive"
  echo 'Do not commit .local-archive/ (see .gitignore.suggested).'
fi
