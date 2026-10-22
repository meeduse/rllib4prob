# Conservative cleanup of fresh

Place this directory at `fresh/scripts/maintenance/`. From `fresh` run:

```bash
bash scripts/maintenance/cleanup.sh --dry-run
```

Check each proposed move. If you agree:

```bash
bash scripts/maintenance/cleanup.sh --apply
```

No files are deleted. Selected legacy folders, `.bak` snapshots, and loose
root logs move to `.local-archive/<timestamp>/` with their paths preserved.
Active Java, all B models, Maven project, tests, all experimental campaigns,
and existing experiment scripts are untouched.

To install the proposed ignore rules ONLY if your root `.gitignore` does not exist:

```bash
if [ ! -e .gitignore ]; then
  cp scripts/maintenance/.gitignore.suggested .gitignore
fi
```

Check Git location BEFORE publishing (the inventory suggests `fresh` is
untracked inside a parent repository):

```bash
git rev-parse --show-toplevel
git rev-parse --show-prefix
git status --short
```

Never run `git init` or `git add .` until you have confirmed which directory
should be published. In particular, keep `experiments/` versionable and
review its contents for personal identifiers before pushing.
