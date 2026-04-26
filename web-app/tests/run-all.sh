#!/usr/bin/env bash
# Runs each spec file as a separate `playwright test` invocation,
# split into two suites:
#   tests/axe/        — accessibility audits (axe-core sweeps)
#   tests/functional/ — client functional / integration tests
#
# Why per-file: Playwright on Windows has a worker-loader race when
# multiple spec files are evaluated in one run — after the first
# file, the loader can lose its "current suite" context and all
# subsequent `test.describe()` calls throw "did not expect
# test.describe() to be called here". Each file in its own Node
# process sidesteps it.
#
# Usage:
#   tests/run-all.sh              # both suites
#   tests/run-all.sh axe          # only axe
#   tests/run-all.sh functional   # only functional
#
# Expects ng serve to already be running at localhost:4200.
set -euo pipefail

cd "$(dirname "$0")/.."

case "${1:-all}" in
  axe)        suites=("tests/axe") ;;
  functional) suites=("tests/functional") ;;
  all|"")     suites=("tests/axe" "tests/functional") ;;
  *) echo "usage: $0 [axe|functional|all]" >&2; exit 2 ;;
esac

fail=0
for dir in "${suites[@]}"; do
  echo "######## $dir ########"
  for spec in "$dir"/*.spec.ts; do
    [ -e "$spec" ] || continue
    echo "==> $spec"
    if ! npx playwright test "$spec" --reporter=list; then
      fail=1
    fi
  done
done

exit "$fail"
