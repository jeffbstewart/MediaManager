#!/usr/bin/env bash
# Runs each a11y spec file as a separate `playwright test` invocation.
#
# Why: Playwright on Windows has a worker-loader race when multiple
# spec files are evaluated in one run — after the first file, the
# loader can lose its "current suite" context and all subsequent
# `test.describe()` calls throw "did not expect test.describe() to
# be called here". Each file in its own Node process sidesteps it.
#
# Expects ng serve to already be running at localhost:4200.
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0
for spec in tests/a11y/*.spec.ts; do
  echo "==> $spec"
  if ! npx playwright test "$spec" --reporter=list; then
    fail=1
  fi
done

exit "$fail"
