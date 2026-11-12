import { DOCUMENT, inject, Injectable, signal } from '@angular/core';

/**
 * The label of the running build, read once from `build-info.json`
 * beside `index.html`. The Dockerfile writes that file at image build
 * time (`BUILD_LABEL`, the merged PR number on a release build); the
 * checked-in copy under `public/` says `dev` for `ng serve`.
 *
 * The file is fetched uncached: it is not content-hashed, so a cached
 * copy would show the previous deploy's label.
 */
@Injectable({ providedIn: 'root' })
export class BuildInfoService {
  private readonly document = inject(DOCUMENT);
  private readonly labelSignal = signal<string | null>(null);
  private loading: Promise<void> | null = null;

  /** The build label, or null until it loads or when it is unavailable. */
  readonly label = this.labelSignal.asReadonly();

  load(): Promise<void> {
    this.loading ??= this.fetchLabel();
    return this.loading;
  }

  private async fetchLabel(): Promise<void> {
    try {
      const url = new URL('build-info.json', this.document.baseURI);
      const res = await fetch(url, { cache: 'no-store', credentials: 'same-origin' });
      if (!res.ok) return;
      const body: unknown = await res.json();
      const label = (body as { label?: unknown } | null)?.label;
      if (typeof label === 'string' && label.trim() !== '') this.labelSignal.set(label.trim());
    } catch {
      // No label is better than a broken shell; the badge stays hidden.
    }
  }
}
