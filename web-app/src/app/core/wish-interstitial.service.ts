import { Injectable, inject, signal } from '@angular/core';
import { CatalogService } from './catalog.service';

/**
 * Manages the first-wish interstitial dialog.
 *
 * The first time a user adds a media wish, we show a dialog explaining
 * that wishes are shared with admins to inform purchase decisions.
 * Once acknowledged, the dialog doesn't show again.
 */
@Injectable({ providedIn: 'root' })
export class WishInterstitialService {
  private readonly catalog = inject(CatalogService);

  /** Whether the user has any existing media wish (loaded from API). */
  private hasAnyWish = false;
  /** Whether the interstitial was acknowledged this session. */
  private acknowledged = false;
  private loaded = false;

  /**
   * Returns true if the interstitial needs to be shown before adding a wish.
   * Call this before performing the wish-add action. If it returns true,
   * show the interstitial dialog and call acknowledge() when the user confirms.
   */
  async needsInterstitial(): Promise<boolean> {
    if (this.acknowledged || this.hasAnyWish) return false;

    if (!this.loaded) {
      try {
        const data = await this.catalog.getWishList();
        this.hasAnyWish = data.has_any_media_wish;
        this.loaded = true;
      } catch {
        return false;
      }
    }

    return !this.hasAnyWish;
  }

  /** Mark the interstitial as acknowledged for this session. */
  acknowledge(): void {
    this.acknowledged = true;
    this.hasAnyWish = true;
  }
}
