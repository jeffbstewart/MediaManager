/**
 * The one place that knows which element scrolls the page, so the
 * header's frosted `is-scrolled` state has a single source.
 *
 * The shell scrolls the document, so it passes `window`; an element
 * target is supported for a view that scrolls a container of its own.
 */
export type ScrollTarget = HTMLElement | Window;

/** Scroll offset past which the header counts as scrolled. */
export const SCROLLED_THRESHOLD_PX = 8;

function offsetOf(target: ScrollTarget): number {
  return target instanceof Window ? target.scrollY : target.scrollTop;
}

/**
 * Report whether `target` is scrolled past the threshold: once now and
 * again whenever the answer changes. Returns a function that removes
 * the listener.
 */
export function watchScrolled(target: ScrollTarget, onChange: (scrolled: boolean) => void): () => void {
  let last = offsetOf(target) > SCROLLED_THRESHOLD_PX;
  onChange(last);
  const listener = (): void => {
    const now = offsetOf(target) > SCROLLED_THRESHOLD_PX;
    if (now !== last) {
      last = now;
      onChange(now);
    }
  };
  target.addEventListener('scroll', listener, { passive: true });
  return () => target.removeEventListener('scroll', listener);
}
