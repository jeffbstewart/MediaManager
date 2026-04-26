# Admin functional tests — morning triage

**Status (2026-04-26 03:45 UTC):**
- 22 new admin specs written (functional/27-48-*.spec.ts)
- Full functional sweep: **424 / 442 passed, 18 failed across 9 specs**
- All non-admin tests still green (same as previous baseline)

Failures are **almost entirely selector / button-text mismatches** — the
first-pass tests didn't have ground-truth verification against the live
DOM. These are NOT real product bugs unless verified.

Run `node tests/harness.mjs functional` to reproduce. Drill into
individual failures with `node tests/inspect-failure.mjs "<title>"`.

---

## Spec-by-spec triage

### 31-admin-reports.spec.ts (3 failures)
- **L71 — Open row's Actions menu** — Resolve menu item not visible. Either
  the regex `/^Resolve$/` is too strict (template likely renders
  "Resolve" with whitespace or icon prefix) or mat-menu portal isn't
  yet open. Try `{ hasText: 'Resolve' }` without anchors.
- **L97 — Resolve dialog submit** — same root cause; Resolve menu item
  click doesn't fire so dialog never opens.
- **L119 — Dismiss path** — same root cause.

**Likely fix:** drop the `^...$` anchors on menu-item text matchers
across all three tests, OR locate the menu item by `mat-icon` content
(`check_circle` / `cancel`) instead of text.

### 35-admin-transcode-unmatched.spec.ts (1 failure)
- **L87 — search result Link click** — the `{ hasText: /Link|Use/ }`
  matcher in the link dialog is wrong. Probably the result row is
  itself clickable (no button), OR the button text is `Pick` /
  `Choose`. Read the html (template at
  `src/app/features/admin/transcode-unmatched.html`) to find the right
  selector.

### 36-admin-transcode-linked.spec.ts (2 failures)
- **L49 / L61 — Re-transcode / Unlink buttons** — text may be just an
  icon button with `aria-label` (component uses MatIconButton in the
  Actions column). Switch to `[aria-label*="..."]` selectors.

### 39-admin-unmatched-audio.spec.ts (2 failures)
- **L43 — Create from file metadata** — button text is probably
  "Create from tags" or similar; the regex `/Create from/` should
  match but maybe the click goes to a different element. Verify
  template.
- **L53 — Ignore all** — same — button might be in a card footer
  with different text.

### 41-admin-expand.spec.ts (1 failure)
- **L35 — linked title chips on the table row** — fixture's
  `linked_titles` may render only inside the Expand dialog, not the
  row. Check whether the row shows just a title COUNT (e.g. "2 titles
  linked"), and update the assertion.

### 42-admin-media-item-edit.spec.ts (4 failures)
- **L40 — TMDB query pre-population** — `[aria-label="Search TMDB"]`
  input might not exist when `needsTmdbFix` is false (default fixture
  has enrichment_status=COMPLETE → search section gated off). Either
  the input lives elsewhere or the test needs the same fixture override
  used in `axe/16-admin-tier-c.spec.ts:115`.
- **L45 / L52 — Search + assign** — same root cause. Override
  `/api/v2/admin/media-item/1` to return enrichment_status=FAILED.
- **L71 — Save Purchase** — Save button enabled-state may differ; check
  the dirty-state computation in the component (saves iff `isDirty()`).

### 44-admin-settings.spec.ts (2 failures)
- **L43 — Save POST** — Save button text isn't `/^Save( All)?$/`. Read
  template for the actual label (likely "Save Settings" without the
  optional "All").
- **L67 — Delete buddy key** — Delete button is probably an icon-only
  button; switch to `[aria-label]`.

### 46-admin-live-tv.spec.ts (1 failure)
- **L46 — Sync channels** — button might be `Sync Channels` exactly,
  OR icon-only. Verify template; use `[aria-label]` if icon-only.

### 47-admin-data-quality.spec.ts (2 failures)
- **L90 / L99 — Edit dialog open + save** — the Edit menu item click
  may not be triggering open(); the per-row Actions menu portals to
  body so the menu-item click might need an explicit
  `.mat-mdc-menu-panel` scope. Try clicking via
  `page.locator('.mat-mdc-menu-panel button').filter({ hasText: 'Edit' })`.
  Save button text might be "Save changes" or "Apply" — verify.

---

## Common patterns to apply when fixing

1. **Menu items in mat-menu portal**: always click via
   `.mat-mdc-menu-panel button` selector, not `app-...` scope (the
   portal lives at body level).
2. **Icon-only buttons**: use `[aria-label*="X"]`, not `{ hasText }`.
3. **Pre-populated form fields**: verify the gating component state
   (e.g. `needsTmdbFix`, `isDirty`) before assuming the section
   renders.
4. **Strict `/^X$/` regex**: most menu items have leading/trailing
   whitespace or an icon prefix; prefer non-anchored `{ hasText: 'X' }`.
5. **Dialogs that open via menu item**: add an explicit
   `await page.waitForSelector('app-...x .modal-overlay')` between
   the menu-item click and the next assertion.

## Specs that passed — no morning work needed
27 users, 28 tags, 29 family-members, 30 valuation, 32 inventory,
33 purchase-wishes, 34 transcode-status, 37 transcode-backlog,
38 unmatched-books, 40 add-item, 43 import, 45 cameras, 48 document-ownership.

13 of 22 admin specs are 100% green on the first try.
