import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/core';
import { ThemeService } from './theme.service';
import { THEME_STORAGE_KEY, readCachedTheme } from './theme-ids';

// One theme ships today (marquee). The service keeps the pin-or-default
// model so a second theme can be added without changing it; these tests
// pin down the single-theme behavior, including a retired id in the
// cache or on the profile.
describe('ThemeService', () => {
  let doc: Document;

  function create(): ThemeService {
    const service = TestBed.inject(ThemeService);
    TestBed.tick();
    return service;
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    doc = TestBed.inject(DOCUMENT);
    delete doc.documentElement.dataset['theme'];
  });

  it('starts from the cached theme and writes it to the document', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'marquee');
    const theme = create();
    expect(theme.effective()).toBe('marquee');
    expect(doc.documentElement.dataset['theme']).toBe('marquee');
  });

  it('falls back to marquee when the cache is empty or unknown', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'neon');
    const theme = create();
    expect(theme.effective()).toBe('marquee');
  });

  it('treats a cached retired theme as the default and overwrites the cache', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'classic');
    const theme = create();
    expect(theme.effective()).toBe('marquee');
    expect(doc.documentElement.dataset['theme']).toBe('marquee');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('marquee');
  });

  it('ignores a retired or unknown pin from the server', () => {
    const theme = create();
    theme.applyFromServer('classic', 'marquee');
    TestBed.tick();
    expect(theme.pinned()).toBeNull();
    expect(theme.effective()).toBe('marquee');
    theme.applyFromServer('neon', 'neon');
    TestBed.tick();
    expect(theme.pinned()).toBeNull();
    expect(theme.effective()).toBe('marquee');
  });

  it('keeps a known pin and clears it on request', () => {
    const theme = create();
    theme.applyFromServer('marquee', 'marquee');
    TestBed.tick();
    expect(theme.pinned()).toBe('marquee');
    theme.pin(null);
    TestBed.tick();
    expect(theme.pinned()).toBeNull();
    expect(doc.documentElement.dataset['theme']).toBe('marquee');
  });
});

describe('readCachedTheme', () => {
  it('survives storage that throws', () => {
    const throwing = { getItem: () => { throw new Error('blocked'); } };
    expect(readCachedTheme(throwing)).toBe('marquee');
  });
});
