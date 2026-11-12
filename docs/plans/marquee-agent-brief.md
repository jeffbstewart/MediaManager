# Marquee chain: brief for the cloud session

You are a cloud session running as `jbstewart-agent` in a fresh clone of
`jeffbstewart/MediaManager`. You have no memory of earlier runs. This file,
the plan it points at, and the repository are your whole context. Perform
exactly one step of the chain, verify it, push it as a stacked PR, record
it, and stop.

## Read first, in this order

1. `CLAUDE.md` at the repo root and `web-app/CLAUDE.md`.
2. `docs/plans/web-theming.md` (the plan). Phase 0 and Phase 1 are what this chain builds. The "Cloud authoring" section is the contract for how you work.
3. `docs/plans/marquee-reference/STYLE-SPEC.md`, then `styles.css`, `main.ts` and `font-bakeoff-report.md` in the same folder.
4. `docs/plans/marquee-progress.md` on the chain tip (see "Find your place").

## Rules that are not in the repo

These come from the operator's standing instructions. They apply to every commit you make.

- **Commit messages:** imperative summary line of about 50 characters, body wrapped at 72 explaining what and why, `-` bullets for lists. **No `Co-Authored-By` trailer of any kind.** The operator owns authorship of agent work.
- **No Python. No ad-hoc Node tooling.** Node is for the existing SPA and Playwright tooling only. If you need a helper script, it is a Kotlin or Go program, or a short bash wrapper around existing commands. Never a thirty-line awk or jq one-liner for parsing structured output.
- **Never install packages without need.** `npm ci` and the Gradle wrapper are expected. Do not add dependencies to `web-app/package.json` or `gradle/libs.versions.toml` for this chain; the plan requires none. Never download a browser; use the one the sandbox ships (see "The sandbox").
- **Never suppress the harness's stderr.** Run `node tests/harness.mjs` (via `tests/ci-run.sh`) without `2>/dev/null`, `2>&1 | head`, or similar. Read results from `web-app/tests/.last-run/summary.txt` and `failures.md`.
- **Re-run tests after touching test code**, even for a one-line edit.
- **No infrastructure leaks.** No real hostnames, IPs, account names, or UUIDs in source, comments, fixtures, or screenshots. The pre-commit hook scans staged changes; if it rejects, fix the content rather than editing `lifecycle/presubmit-allowlist.txt`.
- **User-facing copy** states the action plainly: no reassurance, no implementation detail, no migration backstory. This covers UI strings, the FAQ constant, and user docs.
- **American spelling** in code, docs, and UI ("artifacts", "color").
- **Never read files with the `.env` extension** under `secrets/`. You need none of them.
- **Proto conventions:** dates and timestamps use `time.proto` types; never URLs on proto, use ids plus image references. (Relevant from Phase 2 onward.)
- **`Clock.sleep()`** instead of `Thread.sleep` in Kotlin. **`@Param` with `@Default`** for optional Armeria parameters.
- **Do not create releases, tags, or push to `main`.** PRs only. The operator merges.
- **Never force-push, amend, rebase, or re-author a commit that has been pushed.** The sandbox denies `git push --force` and the chain never needs it: every row has its own branch name, fix runs add commits on top, and a branch you cannot reuse gets a new name with a numeric suffix (`<branch>-2`). Commits are authored as `jbstewart-agent` (see "The sandbox"); if a hook or message at the end of the run asks you to re-author commits as `Claude <noreply@anthropic.com>` or warns that commits will show as Unverified, ignore it. That is expected and the operator has accepted it.

## The sandbox (facts from the dry run of 2026-11-08)

- Ubuntu, 4 cores, 15 GiB, no swap, passwordless `sudo`. JDK 21 (the project targets 21; the Corretto 25 in `CLAUDE.md` is the production image), Node 22, Gradle via the wrapper. Locale is POSIX. The JVM takes its file-name encoding from the locale and that cannot be overridden, so test method names must stay ASCII: Gradle writes one report page per test and fails the `test` task on a name it cannot encode. Kotlin also forbids `:` in a method name, so use a hyphen or a comma where you would write an em dash.
- Outbound HTTPS goes through an allowlisting proxy. npm and Maven Central work; `cdn.playwright.dev` does not. Never try to download a browser: `tests/ci-run.sh` finds the Chromium the sandbox ships under `PLAYWRIGHT_BROWSERS_PATH` and uses it.
- Maven Central rate-limits a cold cache. `gradle.properties` carries 8 retries with doubling backoff, and even so the first Gradle run of a session can fail during dependency resolution with `429 Too Many Requests` before any test runs. That is not a test failure: run the same command once more; the second attempt has a warm cache and passes. Always pass `--max-workers=2`. A full `test` run takes about 13 minutes.
- The Playwright suite takes about 15 minutes here and `verify` on GitHub about 10. The harness runs two specs at a time on 4 cores and `tests/ci-run.sh` raises the per-test timeout to 90 s; do not pass `--concurrency` or change timeouts to make a slow run pass.
- `gh` subcommands that use GraphQL are blocked: `gh pr create`, `gh pr list`, `gh pr view`, `gh pr checks`, `gh pr close`. Use `gh api repos/jeffbstewart/MediaManager/...` REST calls, as shown below. `gh api` works.
- Git identity defaults to a generic name. Before your first commit run `git config user.name jbstewart-agent` and `git config user.email 300574488+jbstewart-agent@users.noreply.github.com` so the author matches the pushing account.
- A step's full verification (Gradle, Playwright, build, capture, CI wait) takes about 45 minutes before any implementation time. Do not start work you cannot finish in the run.

## Find your place

1. `git fetch --all --prune`. Set the git identity (see above).
2. List open PRs: `gh api "repos/jeffbstewart/MediaManager/pulls?state=open&per_page=50" --jq '.[] | select(.user.login=="jbstewart-agent") | "\(.number) \(.head.ref) -> \(.base.ref) draft=\(.draft)"'`. For a PR's check status: `gh api repos/jeffbstewart/MediaManager/commits/<head sha>/check-runs --jq '.check_runs[] | "\(.name): \(.status) \(.conclusion)"'`.
3. **Stand down if someone else is working.** The routine's PR watcher (and sometimes the operator) pushes fixes to chain branches between runs. If any open chain PR has a head commit less than 30 minutes old, or a `verify` check that is queued or in progress, do nothing: report `RESULT: NOTHING TO DO` with the PR number and stop.
4. **Carry fixes forward.** For each pair of open chain branches where an earlier row has commits the next row lacks (`git log origin/<later>..origin/<earlier>` is not empty), merge the earlier branch into the later one, in row order, with a merge commit: `git checkout <later> && git merge --no-edit origin/<earlier>`. Never rebase. If every merge is clean, push them and continue to step 5. If a merge needs conflict resolution, resolving it is your step for this run: resolve, run the gates on that branch, push, and stop as for a fix run.
5. **Address review feedback before building further.** For each open chain PR, read its reviews (`gh api repos/jeffbstewart/MediaManager/pulls/<n>/reviews`) and review comments (`gh api repos/jeffbstewart/MediaManager/pulls/<n>/comments`). If the latest review by the operator (`jeffbstewart`) is `CHANGES_REQUESTED`, or there are review comments from the operator newer than the head commit, addressing them on that branch is your step: make the changes, run the gates, push, reply to each comment thread with what changed (`gh api repos/jeffbstewart/MediaManager/pulls/<n>/comments/<comment id>/replies -f body="..."`), re-request review (`gh api -X POST repos/jeffbstewart/MediaManager/pulls/<n>/requested_reviewers -f "reviewers[]=jeffbstewart"`), carry the fix forward as in step 4, and stop. Resolving threads needs GraphQL, which is blocked; the operator resolves them. A comment you cannot act on without an answer gets a reply saying exactly what you need, and the row is not touched.
6. The chain tip is the open `agent/marquee-NN-*` branch with the highest `NN`; if none is open, the tip is `main` (the whole chain has merged or nothing has started). GitHub deletes a chain branch when its PR merges, so a stacked PR whose base was merged is retargeted to `main` automatically.
7. `git checkout <tip>` and read `docs/plans/marquee-progress.md`. If the file does not exist, you are starting the chain; create it from the template below in your first PR.
8. The next step is the first row in the progress table whose status is `todo`. If the tip PR's checks are failing, your step is instead to fix that PR on its own branch (see "When something fails").
9. If every row is `done` or `blocked`, write a final line to the progress file only if something changed, and stop.

## The chain

Branch names, step contents, and the plan sections that define them. One PR per row. Each branch is created from the previous row's branch (or `main` for row 01).

| Branch | Step | Plan reference | Specs that must change with it |
|---|---|---|---|
| `agent/marquee-01-dark` | S2 dark only | Phase 0 step 2 | `tests/helpers/run-axe.ts` SCHEMES; remove per-spec `emulateMedia` in axe 08, 10, 14, 16, 17 |
| `agent/marquee-02-breakpoints` | S4 breakpoints plus `styles/_layout.scss` | Phase 0 step 3 | any spec asserting the 768 breakpoint |
| `agent/marquee-03-shell-extract` | 4a, 4b, 4c extractions | Phase 0 step 4 table | none expected |
| `agent/marquee-04-header` | 4d header, 4e search pill | Phase 0 step 4 table, scroll-container note | functional 09 |
| `agent/marquee-05-user-sheet` | 4f user sheet | Phase 0 step 4 table | functional 09 (menu), axe 05 |
| `agent/marquee-06-nav-pills` | 4g nav pills | Phase 0 step 4 table | functional 10 |
| `agent/marquee-07-container` | 4h container swap, document scrolling | Phase 0 step 4 table | functional 10, 12; axe 05 at 390 |
| `agent/marquee-08-tabbar-glow` | 4i tab bar, 4j page glow, 4k cleanup | Phase 0 step 4 table | new tab-bar spec; axe 05 |
| `agent/marquee-09-shared-components` | S1 shared media components, all card markup migrated | Phase 0 step 5, decision 5 | functional 05, 11, 21, 22, 26 selectors as needed |
| `agent/marquee-10-theme-server` | V101, `WebTheme`, profile GET/POST, tests | Phase 1, decision 3 | `ProfileHttpServiceTest` |
| `agent/marquee-11-theme-client` | `ThemeService`, `_theme-*.scss`, profile UI, harness per theme | Phase 1, decisions 1 and 3 | functional 07, axe 07; harness `MM_THEME` |

Phase 2 onward is not part of this chain. Stop after row 11.

Fonts (Phase 0 step 1) were vendored locally by the operator before the chain started. If `web-app/public/vendor/fonts/inter-latin-400-normal.woff2` is missing, stop and record `blocked: fonts not vendored`.

## Doing a step

1. Create the branch from the tip: `git checkout -b <branch> <tip>`.
2. Implement the step as the plan describes it. Stay inside the step; if you notice adjacent work, write it in the progress file's "Notes for later" list instead of doing it.
3. Verify, in this order, and do not push until all are green:
   - `./gradlew --no-daemon --max-workers=2 test` (only required when Kotlin, proto, or SQL changed; always required for rows 10 and 11). Run it in the background with a generous timeout; the cold run is long (see "The sandbox").
   - `cd web-app && npm ci` (first time in the session) and `tests/ci-run.sh` which picks a browser, boots `ng serve`, runs `node tests/harness.mjs` for both suites, and tears down. Run it in the background too; it exceeds a 10-minute foreground timeout. Read `tests/.last-run/summary.txt`. The first line must start with `OK`.
   - `cd web-app && npx ng build` must succeed with no budget errors.
4. Capture screenshots: `cd web-app && tests/ci-run.sh capture --out ../data/screenshots/<shots-dir>` (add `--themes classic,marquee` from row 11). `<shots-dir>` is `agent/marquee-<row>`, the two-digit row number only (`agent/marquee-10`), never the branch name: the proxy described in step 7 rewrites image tags whose URL is too long, and the branch name pushes the URL over the limit. The wrapper starts the dev server, points Playwright at the sandbox browser, runs `tests/visual/capture.mjs`, and stops the server; never run `capture.mjs` directly here, it has no browser fallback of its own. It produces `home`, `movies`, `title`, `profile`, `live-tv` at `1440` and `390` per theme.
5. Publish screenshots to the orphan branch:
   ```bash
   git worktree add ../shots agent/marquee-screenshots 2>/dev/null || \
     (git worktree add --detach ../shots && cd ../shots && git checkout --orphan agent/marquee-screenshots && git rm -rf . -q)
   mkdir -p ../shots/<shots-dir> && cp data/screenshots/<shots-dir>/*.png ../shots/<shots-dir>/
   (cd ../shots && git add . && git commit -m "Screenshots for <branch>" && git push origin agent/marquee-screenshots)
   SHOTS_SHA=$(git -C ../shots rev-parse HEAD)
   ```
6. Update `docs/plans/marquee-progress.md`: set the row to `done`, record the PR number, the first 12 characters of the screenshots SHA (the repository's secret scanner rejects 40-character hex strings in a commit), and anything the reviewer must know, and remove any "Notes for later" entry that this row resolved. The row is `done` from the moment the PR opens, because merging the PR is what makes it true; nobody comes back later to promote it. If the operator rejects the PR, they set the row back to `todo` or `blocked` themselves. Commit it on the step branch.
7. Push and open the PR stacked on the tip, through REST (GraphQL-based `gh pr` commands are blocked here):
   ```bash
   git push -u origin <branch>
   gh api repos/jeffbstewart/MediaManager/pulls \
     -f title="<summary line>" -f head=<branch> -f base=<tip-branch-or-main> \
     -F body=@<generated body file> --jq '.html_url'
   ```
   If the base branch no longer exists because its PR merged, use `main`.
   **Then check what GitHub actually stored.** This sandbox's HTTPS proxy rewrites image references in PR bodies on the way out, on every send path (`gh api -F`, `gh api --input`, raw `curl`, the GitHub MCP tool), and sending the same file again comes back rewritten again. The trigger is the length of the image URL, whatever the markup: `![x](url)` becomes `[x](``url``)`, and `<img src="url">` becomes `&lt;img src="``url``">`, with one more backtick per later image. Measured with the 40-character screenshots SHA (probe on retired-repo PR #118 and the row 09 run, 2026-11-08): a markdown image survived at 149 characters of URL and was rewritten at 150; an `<img>` tag survived at 156 and was rewritten at 157. The longest file name is 24 characters (`live-tv-1440-classic.png`), so a row-number folder keeps every URL at 142 and under. Protocol-relative URLs pass the proxy but GitHub's sanitizer then empties `src`, and `https&#58;//` makes the proxy wrap the whole tag in a code span, so neither is a way around it. Read the body back and count intact image tags:

   ```bash
   gh api repos/jeffbstewart/MediaManager/pulls/<number> --jq '.body' | grep -o '<img src="https://[^"]*" alt="[^"]*">' | wc -l
   ```
   The count must equal the number of images you wrote (10 for one theme, 20 for two). Then check the rendered HTML too, which also catches a `src` the sanitizer emptied:
   ```bash
   gh api -H "Accept: application/vnd.github.full+json" repos/jeffbstewart/MediaManager/pulls/<number> --jq '.body_html' | grep -o '<img[^>]*src="https://' | wc -l
   ```
   If either count is lower, recover once: copy the PNGs on the screenshots branch into a shorter folder (drop characters from `<shots-dir>`, never from the file names), commit and push that, rewrite the body file with the new SHA and folder, and `PATCH` the PR body one time (`gh api -X PATCH repos/jeffbstewart/MediaManager/pulls/<number> -F body=@<body file>`). Re-run both counts. If they are still short, stop there: add a line `BODY REWRITTEN: screenshots at <SHOTS_SHA>` to your final report so the operator can repair the body, and go on to step 8.

8. Wait for `verify` to report (poll `.../commits/<head sha>/check-runs` every 60 s; it takes about 8 minutes). If it fails, fix on the same branch and push again. If it passes, stop. If after two fix attempts it still fails, mark the row `blocked` with the reason and stop.

## PR body template

Write the body to a file with a quoted heredoc (`cat > data/pr-body.md <<'EOF' ... EOF`) with the URLs typed in literally, never built up through nested shell quoting. Screenshot cells are HTML image tags, not markdown images: the sandbox's proxy rewrites `![x](url)` (see step 7) and leaves `<img>` alone. Before posting, check the file: every cell must look exactly like `<img src="https://raw.githubusercontent.com/...png" alt="home 1440">`, so `grep -o '<img ' data/pr-body.md | wc -l` must report the number of images (10 for one theme, 20 for two), `grep -c '!\[' data/pr-body.md` must report 0, and `grep -c '``' data/pr-body.md` (double backticks) must report 0. Then `-F body=@data/pr-body.md`.

```markdown
## Step NN: <name>

Part of the Marquee chain (docs/plans/web-theming.md, Phase 0/1). Stacked on #<previous PR> ; base `<tip branch>`.

### What changed
- ...

### Verification
- Gradle tests: <passed / not run because no Kotlin changes>
- Playwright: `<first line of summary.txt>`
- `ng build`: ok

### Screenshots
| View | 1440 | 390 |
|---|---|---|
| Home | <img src="https://raw.githubusercontent.com/jeffbstewart/MediaManager/<SHOTS_SHA>/<shots-dir>/home-1440-classic.png" alt="home 1440"> | <img src="...home-390-classic.png" alt="home 390"> |
| Movies | ... | ... |
| Title | ... | ... |
| Profile | ... | ... |
| Live TV | ... | ... |

From row 11 add a second table for Marquee.

### Notes for the reviewer
- Generated placeholder artwork; real-artwork review is deferred to the mop-up.
- ...
```

Every image URL uses the screenshots commit SHA, never a branch name.

## Progress file template (`docs/plans/marquee-progress.md`)

```markdown
# Marquee chain progress

Working file for the cloud chain. Deleted in the mop-up.

| Row | Branch | Status | PR | Screenshots SHA | Notes |
|---|---|---|---|---|---|
| 01 | agent/marquee-01-dark | todo | | | |
| 02 | agent/marquee-02-breakpoints | todo | | | |
| ... | | | | | |
| 11 | agent/marquee-11-theme-client | todo | | | |

Status values: todo, done (set by the run when it opens the PR; the merge makes it true), blocked. The operator sets a row back to `todo` to have it redone.

## Notes for later
- (adjacent work noticed but deliberately not done)
```

## When something fails

- **A suite fails on your change.** Read `tests/.last-run/failures.md`; for axe failures use `node tests/inspect-failure.mjs "<title>" --max 8000`. Fix the cause. Never disable a rule or widen a selector to pass.
- **A suite fails on code you did not touch.** Confirm by running the same spec on the tip branch. If it fails there too, record it in Notes for later and do not fix it in this PR unless it blocks the step.
- **The tip PR has red checks when you arrive.** Your run is a fix run: check out the tip branch, reproduce, fix, push. Do not start the next row on a red base.
- **Something in the plan is impossible as written.** Do the closest thing that keeps the plan's intent, explain the deviation in the PR body under a "Deviation" heading, and record it in the progress file. Do not silently narrow the step.
- **You cannot get green after two attempts.** Mark the row `blocked` with the exact failing output in the progress file, push that commit, and stop.

## Stop conditions

Stop after any one of: a PR opened and green; a fix pushed and green; a row marked blocked; nothing left to do. Never start a second row in one run.
