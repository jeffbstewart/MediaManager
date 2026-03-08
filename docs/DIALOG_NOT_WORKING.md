# Vaadin 25 Dialog Not Rendering as Overlay

## The Problem

After splitting the monolithic `TranscodeView` into separate sub-pages
(`TranscodeStatusView`, `TranscodeUnmatchedView`, `TranscodeLinkedView`,
`TranscodeBacklogView`), Dialog components rendered **inline at the bottom
of the page** instead of as floating overlays. Clicking "Link" on the
Unmatched page would show the dialog content appended to the page DOM
rather than in a centered modal overlay.

The same Dialog code worked fine on the old unified `TranscodeView` and
continues to work on `CatalogView` and `TitleDetailView`.

## Root Cause

Vaadin 25's **pre-compiled production bundle** uses aggressive code-splitting
via Vite. The `vaadin-dialog` web component is not in the base bundle — it
lives in a **lazy-loaded chunk** (`chunk-b6f90b2b...js`) that only gets
loaded when certain other components trigger a transitive import chain.

### How Vaadin's Chunk Loading Works

1. The server sends a **UIDL response** containing `LAZY` entries with
   `loadOnDemand` hashes for each component the page needs.
2. The client-side function `cu` (in the central chunk `chunk-c1e4ee5a...js`)
   maps each hash to a chunk import.
3. Vite's `__vite__mapDeps` array maps dependency indices to chunk filenames.

### The Dependency Chain

```
Hash 039da0aa... → loads chunk-3d4ab928...js (a "glue" chunk)
                   → which imports chunk-b6f90b2b...js (Dialog chunk)
                     → which defines vaadin-dialog, vaadin-dialog-overlay,
                       vaadin-dialog-content as custom elements (side effect)
```

### Why CatalogView Works

CatalogView uses components (Checkbox, IntegerField) whose UIDL hashes
include `039da0aa...`, which triggers the glue chunk, which transitively
loads the Dialog chunk. Dialog's **own** hashes (`b7810896...`,
`f126cc98...`, `45344692...`) are never present in any page's UIDL — not
even CatalogView's. Dialog always loads as a **transitive dependency** of
another component's chunk, never directly.

### Why the Transcode Sub-Pages Don't Work

The split views only use base-bundle components: Grid, Button, TextField,
ComboBox, HorizontalLayout, VerticalLayout, Icon, Image. None of these
trigger loading of hash `039da0aa...`, so the glue chunk never loads, so
the Dialog chunk never loads. When server-side code calls `Dialog().open()`,
it creates a `<vaadin-dialog>` DOM element, but since the custom element
class was never registered, the browser treats it as an unknown element —
no shadow DOM, no overlay, no backdrop. The dialog content just appears
inline at the bottom of the page.

## What Did NOT Fix It

### `@Uses(Dialog::class)` annotation

This annotation is supposed to tell Vaadin's scanner to include a component
in the bundle. However, with the pre-compiled bundle, the scanner doesn't
run — the bundle is already built. The annotation had zero effect; Dialog's
hashes still did not appear in the UIDL.

### Adding a hidden `Dialog()` to the initial render

```kotlin
add(Dialog())  // Added to the verticalLayout in ui { }
```

This created a `<vaadin-dialog>` element in the DOM, but since the web
component JS wasn't loaded, it caused 7 JavaScript errors:
`$0.requestContentUpdate is not a function`. The element existed but wasn't
functional.

### Adding a hidden `Checkbox` to the initial render

```kotlin
add(Checkbox().apply { isVisible = false })
```

Checkbox is in the **base bundle** (always loaded). It does not trigger any
additional chunk loading. Dialog remained undefined.

## The Fix (Current — Fragile Hack)

Force-load the chunk via JavaScript on page attach:

```kotlin
override fun onAttach(attachEvent: AttachEvent) {
    super.onAttach(attachEvent)
    attachEvent.ui.page.executeJs(
        "window.Vaadin.Flow.loadOnDemand(" +
        "'039da0aa3283c862d809052d1e05f80ce5922aa4f27e77f287ec5bdfeaba3abc')"
    )
}
```

This calls the same internal API that Vaadin's client-side code uses to
load lazy chunks. The hash `039da0aa...` triggers loading the glue chunk,
which transitively imports the Dialog chunk. After the call completes:
- `customElements.get('vaadin-dialog')` returns the class (was `undefined`)
- `customElements.get('vaadin-notification')` also becomes available
  (it's in the same chunk or a sibling)
- Clicking "Link" opens a proper floating overlay dialog

### Why This Is Fragile

**The hash `039da0aa...` is a content hash of the chunk's source code.**
It will change whenever:
- Vaadin is upgraded (even patch versions)
- The pre-compiled bundle is regenerated
- Dependencies change in a way that reshuffles chunk boundaries

When the hash changes, the `loadOnDemand` call will silently fail (or
load the wrong chunk), and Dialogs will break again with no obvious error.

## Better Fixes to Investigate

### 1. Switch to dev mode bundle (not recommended for production)

In dev mode, Vite serves all components without code-splitting. Dialog
would always be available. But dev mode is slower and not meant for
production.

### 2. Force Vaadin to include Dialog in the base bundle

Vaadin's `vaadin-maven-plugin` / Gradle plugin has configuration for
controlling which components are eagerly loaded. Investigate whether
`build.gradle.kts` can configure `vaadinBuildFrontend` to include Dialog
in the main chunk rather than a lazy chunk. This might be possible via
a custom `vite.config.ts` or Vaadin's `vaadin.whitelisted-packages`
configuration.

### 3. Add a component that naturally triggers the chain

Find a lightweight Vaadin component that:
- Is in the UIDL hash set that includes `039da0aa...`
- Can be added invisibly to the page layout
- Is always in the initial render (not just in click handlers)

The old unified TranscodeView happened to have this implicitly because
it had a broader component mix (ProgressBar, etc.) that triggered the
right hash chain.

### 4. Custom Vite configuration to merge Dialog into base chunk

Create a `vite.config.ts` override that puts `@vaadin/dialog` into the
manual chunks configuration, forcing it into the base bundle. This is the
most robust solution but requires understanding Vaadin's Vite integration.

### 5. Use `@JsModule` to force-import Dialog

```kotlin
@JsModule("@vaadin/dialog/src/vaadin-dialog.js")
class TranscodeUnmatchedView : KComposite() { ... }
```

This might force the bundler to include Dialog's module when the view is
loaded. Untested — may not work with the pre-compiled bundle since the
bundler doesn't process server-side annotations at runtime.

## Diagnostic Techniques Used

These techniques were useful for diagnosing this issue and may help if it
recurs:

### Check if a custom element is registered
```javascript
// In browser console or via Playwright evaluate
customElements.get('vaadin-dialog')  // undefined = not loaded
```

### Intercept UIDL responses to see lazy-load hashes
```javascript
// Monkey-patch XMLHttpRequest to capture UIDL responses
const origSend = XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.send = function() {
    this.addEventListener('load', function() {
        if (this.responseURL.includes('v-r=uidl')) {
            const data = JSON.parse(this.responseText.substring(
                this.responseText.indexOf('{')));
            const lazy = [];
            JSON.stringify(data, (k, v) => {
                if (k === 'loadOnDemand') lazy.push(v);
                return v;
            });
            console.log('UIDL hashes:', lazy);
        }
    });
    origSend.apply(this, arguments);
};
```

### Test if a specific hash loads Dialog
```javascript
await window.Vaadin.Flow.loadOnDemand('039da0aa...');
console.log(!!customElements.get('vaadin-dialog'));  // true if it worked
```

### Compare network requests between working and broken pages
Save `browser_network_requests` output for both a working page (e.g.,
`/catalog`) and the broken page. Look for extra JS chunk files loaded
on the working page but not the broken one.

## Timeline

- **Initial symptom**: Dialog renders inline after splitting TranscodeView
- **First hypothesis**: Nested route paths (`transcodes/unmatched`) broke it
  → Disproved: path structure doesn't affect chunk loading
- **Second hypothesis**: Missing component from old unified page
  → Correct direction: old page had components that triggered chunk loading
- **Tried `@Uses(Dialog::class)`** → No effect with pre-compiled bundle
- **Tried hidden `Dialog()`** → 7 JS errors, element exists but not functional
- **Tried hidden `Checkbox()`** → In base bundle, no chunk loading triggered
- **Analyzed UIDL hashes** → Found `039da0aa...` present on CatalogView
  but absent from Transcode views
- **Traced chunk dependency chain** → `039da0aa...` → glue chunk → Dialog chunk
- **Tested `loadOnDemand` via Playwright** → Dialog became functional
- **Implemented `onAttach` + `executeJs`** → Fix confirmed working
