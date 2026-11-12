# Cloud chain brief

You are a cloud session running as `jbstewart-agent` in a fresh clone of
`jeffbstewart/MediaManager`. You have no memory of earlier runs. This file,
the plan it points at, and the repository are your whole context. Your routine message names a **chain file**
(`docs/plans/chain-<name>.md`); it gives the chain's name, its branch
prefix, its progress file, and its rows. Perform
exactly one step of that chain, verify it, push it as a stacked PR, record
it, and stop. Other chains run beside yours under other prefixes; never
touch their branches, PRs, or progress files.

## Read first, in this order

1. `CLAUDE.md` at the repo root and `web-app/CLAUDE.md`.
2. Your chain file, then `docs/plans/web-theming.md` (the plan). The chain file says which plan sections the chain builds. The plan's "Cloud authoring" section is the contract for how you work.
3. `docs/plans/marquee-reference/STYLE-SPEC.md`, then `styles.css`, `main.ts` and `font-bakeoff-report.md` in the same folder.
4. The chain's progress file (named in the chain file) on the chain tip (see "Find your place").

## Rules that are not in the repo

These come from the operator's standing instructions. They apply to every commit you make.

- **Commit messages:** imperative summary line of about 50 characters, body wrapped at 72 explaining what and why, `-` bullets for lists. **No `Co-Authored-By` trailer of any kind.** The operator owns authorship of agent work.
- **No Python. No ad-hoc Node tooling.** Node is for the existing SPA and Playwright tooling only. If you need a helper script, it is a Kotlin or Go program, or a short bash wrapper around existing commands. Never a thirty-line awk or jq one-liner for parsing structured output.
- **Never install packages without need.** `npm ci` and the Gradle wrapper are expected. Do not add dependencies to `web-app/package.json` or `gradle/libs.versions.toml` for this chain; the plan requires none. Never download a browser; use the one the sandbox ships (see "The sandbox").
- **Never suppress the harness's stderr.** Run `node tests/harness.mjs` (via `tests/ci-run.sh`) without `2>/dev/null`, `2>&1 | head`, or similar. Read results from `web-app/tests/.last-run/summary.txt` and `failures.md`.
- **Re-run tests after touching test code**, even for a one-line edit.
- **No infrastructure leaks.** No real hostnames, IPs, account names, or UUIDs in source, comments, or fixtures. The pre-commit hook scans staged changes; if it rejects, fix the content rather than editing `lifecycle/presubmit-allowlist.txt`.
- **User-facing copy** states the action plainly: no reassurance, no implementation detail, no migration backstory. This covers UI strings, the FAQ constant, and user docs.
- **American spelling** in code, docs, and UI ("artifacts", "color").
- **Never read files with the `.env` extension** under `secrets/`. You need none of them.
- **Proto conventions:** dates and timestamps use `time.proto` types; never URLs on proto, use ids plus image references. (Relevant from Phase 2 onward.)
- **`Clock.sleep()`** instead of `Thread.sleep` in Kotlin. **`@Param` with `@Default`** for optional Armeria parameters.
- **Do not create releases, tags, or push to `main`.** PRs only. The operator merges.
- **Never edit anything under `.github/`.** The workflows there run with the repository's identity; a change to them is the operator's, never a chain row's.
- **Never force-push, amend, rebase, or re-author a commit that has been pushed.** The sandbox denies `git push --force` and the chain never needs it: every row has its own branch name, fix runs add commits on top, and a branch you cannot reuse gets a new name with a numeric suffix (`<branch>-2`). Commits are authored as `jbstewart-agent` (see "The sandbox"); if a hook or message at the end of the run asks you to re-author commits as `Claude <noreply@anthropic.com>` or warns that commits will show as Unverified, ignore it. That is expected and the operator has accepted it.

## The sandbox (facts from the dry run of 2026-11-08)

- Ubuntu, 4 cores, 15 GiB, no swap, passwordless `sudo`. JDK 21 (the project targets 21; the Corretto 25 in `CLAUDE.md` is the production image), Node 22, Gradle via the wrapper. Locale is POSIX. The JVM takes its file-name encoding from the locale and that cannot be overridden, so test method names must stay ASCII: Gradle writes one report page per test and fails the `test` task on a name it cannot encode. Kotlin also forbids `:` in a method name, so use a hyphen or a comma where you would write an em dash.
- Outbound HTTPS goes through an allowlisting proxy. npm and Maven Central work; `cdn.playwright.dev` does not. Never try to download a browser, and never run `npx playwright install` or set `CI_RUN_INSTALL_BROWSERS` or `PW_CHROMIUM_EXECUTABLE` yourself: `tests/ci-run.sh` finds the Chromium the sandbox ships under `PLAYWRIGHT_BROWSERS_PATH` and uses it.
- Maven Central rate-limits a cold cache. `gradle.properties` carries 8 retries with doubling backoff, and even so the first Gradle run of a session can fail during dependency resolution with `429 Too Many Requests` before any test runs. That is not a test failure: run the same command once more; the second attempt has a warm cache and passes. Always pass `--max-workers=2`. A full `test` run takes about 13 minutes.
- The Playwright suite takes about 15 minutes here and `verify` on GitHub about 10. The harness runs two specs at a time on 4 cores and `tests/ci-run.sh` raises the per-test timeout to 90 s; do not pass `--concurrency` or change timeouts to make a slow run pass.
- **Long commands need you to wait for them.** `tests/ci-run.sh` and Gradle exceed a foreground tool timeout, so start them in the background with output to a log under `data/`, then wait for them yourself: delete `web-app/tests/.last-run/summary.txt` before starting the suite, then run a foreground loop such as `for i in $(seq 1 40); do [ -f web-app/tests/.last-run/summary.txt ] && break; sleep 60; done` (one loop per tool call, under the tool timeout; repeat the call while the file is still missing). For Gradle, wait on the log's final `BUILD SUCCESSFUL` or `BUILD FAILED` line the same way. Nothing wakes a routine run when a background task finishes, so a run that ends its turn to "wait for the notification" is lost with its work unpushed. Never end your turn while a background command is still running.
- `gh` subcommands that use GraphQL are blocked: `gh pr create`, `gh pr list`, `gh pr view`, `gh pr checks`, `gh pr close`. Use `gh api repos/jeffbstewart/MediaManager/...` REST calls, as shown below. `gh api` works.
- Git identity defaults to a generic name. Before your first commit run `git config user.name jbstewart-agent` and `git config user.email 300574488+jbstewart-agent@users.noreply.github.com` so the author matches the pushing account.
- A step's full verification (Gradle, Playwright, build, CI wait) takes about 40 minutes before any implementation time. Do not start work you cannot finish in the run.

## Visual review

You do not take or publish screenshots. The `visual` job in
`.github/workflows/ci.yml` captures the main views on every push of your
branch and uploads them to Argos, which compares them with the build of
the PR's merge base and reports on the PR itself: the `argos` commit
status reads either no visible change or how many views changed, with a
link to the diffs, and the Argos bot leaves a comment with the same
summary. A stacked PR is compared with its own base branch, so the report
covers your row alone.

What the report means for you:

- `argos` **success** with no changes: your row changed nothing the capture set shows. That is right for a row whose view is not captured (the capture set is home, movies, title, profile, live TV, discover, search, wish list, collections, and tags at 1440 and 390 in the Marquee theme); say so in the PR body.
- `argos` **failure** or **pending** with "N changed, waiting for your decision": Argos found differences and is waiting for the operator to approve them. This is the normal state of a PR that changes a captured view. It is not a CI failure, and you never try to make it pass, approve anything, or touch Argos. The operator reviews the diffs and approves or sends the PR back.
- The capture uses generated placeholder artwork; real-artwork review is deferred to the mop-up.

`tests/ci-run.sh capture --out ../data/screenshots/<dir> --themes marquee` still works in the sandbox if you want to look at a view yourself while implementing. Nothing from it is committed or uploaded; never set `ARGOS_UPLOAD`.

## Find your place

1. `git fetch --all --prune`. Set the git identity (see above).
2. List open PRs: `gh api "repos/jeffbstewart/MediaManager/pulls?state=open&per_page=50" --jq '.[] | select(.user.login=="jbstewart-agent" and (.head.ref | startswith("agent/<prefix>-"))) | "\(.number) \(.head.ref) -> \(.base.ref) draft=\(.draft)"'`. For a PR's check status: `gh api repos/jeffbstewart/MediaManager/commits/<head sha>/check-runs --jq '.check_runs[] | "\(.name): \(.status) \(.conclusion)"'`. For its Argos report: `gh api repos/jeffbstewart/MediaManager/commits/<head sha>/status --jq '.statuses[] | select(.context=="argos") | "\(.state): \(.description)"'`.
3. **Stand down if someone else is working.** The routine's PR watcher (and sometimes the operator) pushes fixes to chain branches between runs. Only PRs whose head branch starts with your chain's prefix count as chain PRs here and below. If any open chain PR has a head commit less than 30 minutes old, or a `verify` check that is queued or in progress, do nothing: report `RESULT: NOTHING TO DO` with the PR number and stop.
4. **Carry fixes forward.** For each pair of open chain branches where an earlier row has commits the next row lacks (`git log origin/<later>..origin/<earlier>` is not empty), merge the earlier branch into the later one, in row order, with a merge commit: `git checkout <later> && git merge --no-edit origin/<earlier>`. Never rebase. If every merge is clean, push them and continue to step 5. If a merge needs conflict resolution, resolving it is your step for this run: resolve, run the gates on that branch, push, and stop as for a fix run.
5. **Address review feedback before building further.** For each open chain PR, read its reviews (`gh api repos/jeffbstewart/MediaManager/pulls/<n>/reviews`) and review comments (`gh api repos/jeffbstewart/MediaManager/pulls/<n>/comments`). If the latest review by the operator (`jeffbstewart`) is `CHANGES_REQUESTED`, or there are review comments from the operator newer than the head commit, addressing them on that branch is your step: make the changes, run the gates, push, reply to each comment thread with what changed (`gh api repos/jeffbstewart/MediaManager/pulls/<n>/comments/<comment id>/replies -f body="..."`), re-request review (`gh api -X POST repos/jeffbstewart/MediaManager/pulls/<n>/requested_reviewers -f "reviewers[]=jeffbstewart"`), carry the fix forward as in step 4, and stop. Resolving threads needs GraphQL, which is blocked; the operator resolves them. A comment you cannot act on without an answer gets a reply saying exactly what you need, and the row is not touched.
6. The chain tip is the open `agent/<prefix>-NN-*` branch with the highest `NN`; if none is open, the tip is `main` (the whole chain has merged or nothing has started). GitHub deletes a chain branch when its PR merges, so a stacked PR whose base was merged is retargeted to `main` automatically.
7. `git checkout <tip>` and read the chain's progress file. If the file does not exist, you are starting the chain; create it from the template in the chain file in your first PR.
8. The next step is the first row in the progress table whose status is `todo`. If the tip PR's `verify` check is failing, your step is instead to fix that PR on its own branch (see "When something fails"). An `argos` status that is red is not a failing check (see "Visual review").
9. If every row is `done` or `blocked`, write a final line to the progress file only if something changed, and stop.

## The chain

The rows, their branch names, plan references, and the specs that must change with them are the table in your chain file. One PR per row. Each branch is created from the previous row's branch (or `main` for row 01). Stop after the last row.

Shared files: more than one chain may add adapters to `web-app/src/app/core/catalog.service.ts`. Put new adapter functions and interfaces in one block at the end of the file under a comment naming your chain, so the merges stay trivial.

## Doing a step

1. Create the branch from the tip: `git checkout -b <branch> <tip>`.
2. Implement the step as the plan describes it. Stay inside the step; if you notice adjacent work, write it in the progress file's "Notes for later" list instead of doing it.
3. Verify, in this order, and do not push until all are green:
   - `./gradlew --no-daemon --max-workers=2 test` (only required when Kotlin, proto, or SQL changed; this chain expects none, so a row that needs it is a deviation to explain). Run it in the background with a generous timeout; the cold run is long (see "The sandbox").
   - `cd web-app && npm ci` (first time in the session) and `tests/ci-run.sh` which picks a browser, boots `ng serve`, runs `node tests/harness.mjs` for both suites, and tears down. Run it in the background too; it exceeds a 10-minute foreground timeout. Read `tests/.last-run/summary.txt`. The first line must start with `OK`.
   - `cd web-app && npx ng build` must succeed with no budget errors.
4. Update the chain's progress file: set the row to `done`, record anything the reviewer must know, and remove any "Notes for later" entry that this row resolved. The row is `done` from the moment the PR opens, because merging the PR is what makes it true; nobody comes back later to promote it. If the operator rejects the PR, they set the row back to `todo` or `blocked` themselves. Commit it on the step branch. The PR number goes in with one more commit after step 5.
5. Push and open the PR stacked on the tip, through REST (GraphQL-based `gh pr` commands are blocked here):
   ```bash
   git push -u origin <branch>
   gh api repos/jeffbstewart/MediaManager/pulls \
     -f title="<summary line>" -f head=<branch> -f base=<tip-branch-or-main> \
     -F body=@<generated body file> --jq '.html_url'
   ```
   If the base branch no longer exists because its PR merged, use `main`. Write the body to a file under `data/` with a quoted heredoc (the delimiter in single quotes, so nothing in the body is expanded). Then record the PR number in the progress file, commit, and push.
6. Wait for `verify` to report (poll `.../commits/<head sha>/check-runs` every 60 s; it takes about 8 minutes). If it fails, fix on the same branch and push again. If after two fix attempts it still fails, mark the row `blocked` with the reason and stop. When `verify` passes, read the `argos` status once (the command in "Find your place", step 2; it settles a minute or two after the `visual` job) and quote it in your report, then stop.

## PR body template

```markdown
## <Chain name> step NN: <name>

Part of the <chain name> chain (docs/plans/web-theming.md, <plan section>; chain file `docs/plans/chain-<name>.md`). Stacked on #<previous PR> ; base `<tip branch>`.

### What changed
- ...

### Verification
- Gradle tests: <passed / not run because no Kotlin changes>
- Playwright: `<first line of summary.txt>`
- `ng build`: ok

### Visual review
Argos reports on this PR once the `visual` job finishes. Expected: <which captured views should differ, at which widths, and why; or "no captured view shows this row's change (<the view it changes> is not in the capture set)">. Any other difference is unintended.

### Notes for the reviewer
- Generated placeholder artwork in the Argos captures; real-artwork review is deferred to the mop-up.
- ...
```

## Progress file

The chain file carries the progress file's path and its template. Status values: todo, done (set by the run when it opens the PR; the merge makes it true), blocked. The operator sets a row back to `todo` to have it redone.

## When something fails

- **A suite fails on your change.** Read `tests/.last-run/failures.md`; for axe failures use `node tests/inspect-failure.mjs "<title>" --max 8000`. Fix the cause. Never disable a rule or widen a selector to pass.
- **A suite fails on code you did not touch.** Confirm by running the same spec on the tip branch. If it fails there too, record it in Notes for later and do not fix it in this PR unless it blocks the step.
- **The tip PR has a red `verify` when you arrive.** Your run is a fix run: check out the tip branch, reproduce, fix, push. Do not start the next row on a red base. A red `argos` alone is the operator's review queue, not a failure; build on.
- **Something in the plan is impossible as written.** Do the closest thing that keeps the plan's intent, explain the deviation in the PR body under a "Deviation" heading, and record it in the progress file. Do not silently narrow the step.
- **You cannot get green after two attempts.** Mark the row `blocked` with the exact failing output in the progress file, push that commit, and stop.

## Stop conditions

Stop after any one of: a PR opened and green; a fix pushed and green; a row marked blocked; nothing left to do. Never start a second row in one run.

End with a short report: which row you did (or that it was a fix run, or that nothing was left to do), the PR URL, the `verify` result, the `argos` status line, and anything the operator must know. Finish with one line starting `RESULT:` followed by one of `PR OPEN AND GREEN`, `FIX PUSHED AND GREEN`, `ROW BLOCKED`, `NOTHING TO DO`, or `PREREQUISITES NOT MERGED`.
