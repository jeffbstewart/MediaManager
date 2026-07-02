## Your environment: no internet, no local toolchain

You are running inside a locked-down container with **no access to the
internet** and **no build tools** (no compiler, no Gradle, no test runner).
You cannot `curl`, `pip install`, `npm install`, fetch dependencies, or
reach any external service. Do not attempt it, and do not write code that
assumes network access at build or test time.

You **can** read and edit files in this workspace, and you can build and
test the project through the **builder** tools described below.

## How to build and test: the `builder` MCP tools

Building and running tests happens in a separate, jailed **builder**
service exposed to you as MCP tools. The exact tools come from this
project's `agent-harness.yaml`; call **`harness_info`** to see the live
menu, then use the named actions. Typically:

- **`build`** - compile the project (no tests).
- **`test`** - run the test suite. Accepts a `filter` argument to run a
  subset, e.g. `filter: "TranscodeMatcherServiceTest"` or
  `filter: "net.stewart.mediamanager.service.*"`. Prefer a filter while
  iterating; run the full suite before declaring done.
- **`get_log(runId)`** - fetch the full log of a prior run. Action results
  are a compact digest (status, parsed compile errors, failed tests, a
  short tail). When the digest is not enough, page the real log with
  `get_log` using the `runId` from the digest.

Each action runs to completion and returns the digest - a build can take
minutes; wait for it. Only one build runs at a time; if you get
`status: "busy"`, another run is in progress - check it with `get_log` on
the active `runId` or wait and retry.

## The rule that matters most: verify before you claim success

You have real build and test tools, so **never say a change compiles,
builds, passes tests, or is "done" until a builder action confirms it.**
Concretely:

1. Make the edit.
2. Call `build` (and `test`, with a `filter` for the affected area).
3. Read the digest. If it failed, use the parsed errors / `get_log` to fix,
   and repeat.
4. Only report success after a green run - quote the `runId`.

"It should work" is not verification. A green builder result is.

## Watching your own work

A human can watch your builds live (status, audit trail, full logs) on a
local status page. Everything you run through the builder is recorded in an
append-only audit log. Work as if each action is observed - because it is.

## Project specifics

- **Language / build system:** Server: Kotlin + Gradle (JDK 25), with protocol buffer messages Client: Angular material + npm, not yet supported by the build harness
- **Dependencies are pre-cached.** Builds run offline. If you genuinely
  need a new dependency, you cannot add it yourself - say so and stop; a
  human must run the dependency airlock. Do not restructure the build to
  work around a missing library.
- **Layout:** read CLAUDE.md for details
