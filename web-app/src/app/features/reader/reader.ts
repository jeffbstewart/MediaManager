import {
  Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy,
  ElementRef, viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { CatalogService } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

/**
 * Minimal epub.js surface used by the reader. The full API is large — only the
 * bits we touch are typed here.
 */
interface EpubJsRendition {
  display(target?: string): Promise<unknown>;
  on(event: 'relocated', handler: (loc: EpubJsLocation) => void): void;
  themes: { fontSize(size: string): void };
  next(): void;
  prev(): void;
  destroy(): void;
}
interface EpubJsLocation {
  start: { cfi: string; percentage: number };
  end: { cfi: string; percentage: number };
}
interface EpubJsBook {
  renderTo(el: HTMLElement, opts: Record<string, unknown>): EpubJsRendition;
  destroy(): void;
}
declare global {
  interface Window {
    // `openAs: 'epub'` forces the library to treat the URL as a binary
    // archive (default is to sniff by file extension, which fails for our
    // /ebook/{id} paths that don't end in ".epub").
    ePub?: (url: string, options?: { openAs?: 'epub' | 'directory' | 'binary' }) => EpubJsBook;
  }
}

// Vendored under web-app/public/vendor/ — see THIRD_PARTY_LICENSES.md.
// epub.js's UMD wrapper expects window.JSZip to exist when it loads, so
// JSZip must be injected first.
const JSZIP_SRC = 'vendor/jszip.min.js';
const EPUB_JS_SRC = 'vendor/epub.min.js';
const PROGRESS_REPORT_MS = 10_000;

@Component({
  selector: 'app-reader',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatButtonModule],
  templateUrl: './reader.html',
  styleUrl: './reader.scss',
})
export class ReaderComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly mediaFormat = signal<'EBOOK_EPUB' | 'EBOOK_PDF' | null>(null);
  readonly percent = signal(0);
  readonly fontSize = signal(100);
  readonly ebookSrc = signal('');  // PDF mode uses this directly.

  private mediaItemId = 0;
  private rendition: EpubJsRendition | null = null;
  private book: EpubJsBook | null = null;
  private lastCfi: string | null = null;
  private reportTimer: ReturnType<typeof setInterval> | null = null;
  private readonly container = viewChild<ElementRef<HTMLDivElement>>('epubContainer');

  async ngOnInit(): Promise<void> {
    this.mediaItemId = Number(this.route.snapshot.paramMap.get('mediaItemId'));
    if (!this.mediaItemId) { this.error.set('Invalid ID'); this.loading.set(false); return; }
    this.ebookSrc.set(`/ebook/${this.mediaItemId}`);

    // Discover format by peeking at the reading-progress response (which we'd
    // want anyway for resume). Format info comes from title-detail in real use;
    // for the reader we'll probe the URL instead since we only have the ID.
    try {
      const progress = await this.catalog.getReadingProgress(this.mediaItemId);
      this.percent.set(Math.round((progress.percent ?? 0) * 100));
      // We need to know EPUB vs PDF to render. HEAD the URL for Content-Type.
      const head = await fetch(this.ebookSrc(), { method: 'HEAD' });
      const ct = head.headers.get('content-type') ?? '';
      const fmt: 'EBOOK_EPUB' | 'EBOOK_PDF' =
        ct.includes('application/pdf') ? 'EBOOK_PDF' : 'EBOOK_EPUB';
      this.mediaFormat.set(fmt);
      // Flip loading off *before* bootEpub so the #epubContainer branch of
      // the template enters the DOM. Otherwise viewChild() resolves to
      // undefined and bootEpub throws "epub container missing".
      this.loading.set(false);

      if (fmt === 'EBOOK_EPUB') {
        // Wait one animation frame so Angular commits the DOM change from
        // the loading/mediaFormat signal updates above. afterNextRender
        // would be stricter, but rAF is simpler and sufficient here.
        await new Promise<void>(r => requestAnimationFrame(() => r()));
        await this.bootEpub(progress.cfi);
      }
      // PDF path: the template binds an <iframe> to ebookSrc(); nothing else to do.
    } catch (e) {
      this.error.set('Failed to load book');
      this.loading.set(false);
      console.error('reader init', e);
    }
  }

  ngOnDestroy(): void {
    if (this.reportTimer) { clearInterval(this.reportTimer); this.reportTimer = null; }
    this.rendition?.destroy();
    this.book?.destroy();
    // Fire one last report so closing the tab doesn't lose the tail of progress.
    if (this.lastCfi) void this.reportProgress(true);
  }

  close(): void {
    this.router.navigate([this.routes.home()]);
  }

  increaseFont(): void { this.fontSize.update(v => Math.min(200, v + 10)); this.applyFont(); }
  decreaseFont(): void { this.fontSize.update(v => Math.max(60, v - 10)); this.applyFont(); }
  nextPage(): void { this.rendition?.next(); }
  prevPage(): void { this.rendition?.prev(); }

  private applyFont(): void {
    this.rendition?.themes.fontSize(`${this.fontSize()}%`);
  }

  private async bootEpub(resumeCfi: string | null): Promise<void> {
    await loadScriptOnce(JSZIP_SRC);
    await loadScriptOnce(EPUB_JS_SRC);
    const el = this.container()?.nativeElement;
    if (!el) throw new Error('epub container missing');
    if (!window.ePub) throw new Error('epub.js failed to load');

    const book = window.ePub(this.ebookSrc(), { openAs: 'epub' });
    this.book = book;
    const rendition = book.renderTo(el, {
      width: '100%',
      height: '100%',
      flow: 'paginated',
      allowScriptedContent: false,
    });
    this.rendition = rendition;
    rendition.themes.fontSize(`${this.fontSize()}%`);
    rendition.on('relocated', (loc) => {
      this.lastCfi = loc.start.cfi;
      this.percent.set(Math.round((loc.start.percentage ?? 0) * 100));
    });

    await rendition.display(resumeCfi ?? undefined);

    // Report progress every 10 s; suppresses duplicate reports when the user
    // hasn't moved since the last tick.
    this.reportTimer = setInterval(() => { void this.reportProgress(false); }, PROGRESS_REPORT_MS);
  }

  private async reportProgress(force: boolean): Promise<void> {
    const cfi = this.lastCfi;
    if (!cfi) return;
    const pct = this.percent() / 100;
    try {
      await this.catalog.saveReadingProgress(this.mediaItemId, cfi, pct);
    } catch (e) {
      if (force) console.warn('final reading-progress report failed', e);
    }
  }
}

const loadedScripts = new Set<string>();
function loadScriptOnce(src: string): Promise<void> {
  if (loadedScripts.has(src)) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.async = true;
    s.onload = () => { loadedScripts.add(src); resolve(); };
    s.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(s);
  });
}
