# Font bake-off: free fonts vs. Plex's `circularBold`

**Winner: Figtree, weight 800.** Combined score 69.8/100. It won 99.95% of
2,000 bootstrap resamples of the samples. It is now the heading font in the build.

Only the captured screenshots were used. No Plex files were read or fetched.

## Ranking (each family at its best weight)

| # | Font | Weight | Width error | Height error | IoU | SSIM | Chamfer (px) | Glyph IoU | Score |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Figtree | 800 | 0.7% | +2.0% | 0.743 | 0.776 | 0.438 | 0.778 | **69.8** |
| 2 | Outfit | 800 | 1.1% | +2.8% | 0.700 | 0.697 | 0.499 | 0.784 | 64.4 |
| 3 | Gantari | 900 | 1.1% | −3.3% | 0.705 | 0.708 | 0.525 | 0.792 | 64.0 |
| 4 | Albert Sans | 800 | 1.4% | −0.1% | 0.659 | 0.644 | 0.534 | 0.790 | 61.5 |
| 5 | Lato | 900 | 1.7% | +2.2% | 0.664 | 0.678 | 0.529 | 0.723 | 58.8 |
| 6 | Hanken Grotesk | 900 | 1.6% | −1.1% | 0.653 | 0.643 | 0.566 | 0.737 | 58.6 |
| 7 | Gabarito | 800 | 3.6% | +1.0% | 0.704 | 0.721 | 0.507 | 0.796 | 58.5 |
| 8 | Funnel Sans | 800 | 1.8% | −1.6% | 0.638 | 0.642 | 0.583 | 0.712 | 57.5 |
| 32 | Inter (control) | 600 | ~5% | | | | | | 40.4 |

The full 42-row table is in `table.txt` and `results-all.csv`. Side-by-side renders
are in `contact-sheet.png`.

**Common "Circular look-alikes" at weight 700** (score, signed width error):
- Figtree 64.7 (−1.3%), Outfit 61.6 (−2.0%), Albert Sans 60.9 (+0.3%)
- DM Sans 46.8 (+4.0%), Plus Jakarta Sans 42.6 (+3.2%), Manrope 40.1 (+2.8%)
- Poppins 24.0 (+9.2%), Montserrat 28.6 (+13.1%), Sora 25.4 (+10.6%), Lexend 23.0 (+10.0%)

Several fonts people often suggest as Circular stand-ins come out far too wide.

## Method

- **Samples:** 23 heading strings from `capture/screenshot-1440.png` (rendered at 1x, greyscale anti-aliasing):
  - 1 h1 at 32px;
  - 15 h2s at 21.008px;
  - 7 FAQ questions at 16px.

  Tags and chevrons were cropped out. The ink box of each sample was measured to a fraction of a pixel.
- **Candidates:** 42 free families from Fontsource, each at weights 600, 700, 800 and 900 where available.
  - Strings were shaped with HarfBuzz (kerning and fractional character widths, as in a browser).
  - They were drawn unhinted with FreeType at the exact CSS size.
- **Scores:** each sub-score runs from 0 to 1. The thresholds were fixed before any results were seen.

  | Measure | Weight | Scores 1 at | Scores 0 at |
  |---|---|---|---|
  | Width error | 30% | 0% | ±10% |
  | Height error | 10% | 0% | ±10% |
  | Mask IoU | 15% | 1.0 | 0.5 |
  | SSIM | 15% | 1.0 | 0.5 |
  | Chamfer distance between letter outlines | 15% | 0 px | 1.5 px |
  | Letter IoU on telling glyphs (a e g t y W M & ? k R f s S) | 15% | 1.0 | 0.5 |

  For the shape measures, each candidate was stretched onto the sample's ink box. Proportion errors therefore only count in the width and height scores.
- **Stability:**
  - Figtree places first in 99.95% of resamples. Outfit is in the top 3 in 97.6%, Gantari in 91.4%.
  - Rankings agree between size groups: Spearman 0.75 to 0.92.
  - Rerunning with the screenshot artificially thinned and thickened left Figtree first (67.8 and 70.6).

## Caveats

- The samples are small (16–32px), so treat differences of about 2 points as noise.
- Chrome on Windows renders white-on-dark text with a contrast boost, which favours heavier weights. That is part of why weight 800 beats 700.
- Letter-spacing on the original can't be seen, so it is folded into the width error.
- Fonts outside Fontsource weren't tested, for example Satoshi and General Sans from Fontshare.
- On the h1 alone, which sits on a purple highlight, Instrument Sans scored best.

## Other objective font-matching options

- **WhatTheFont (MyFonts):** image-based identifier that leans commercial. Needs the screenshot uploaded to a third party.
- **Font Squirrel Matcherator:** image-based and leans free. Needs an upload.
- **WhatFontIs:** image-based, with a free-fonts-only filter. Needs an upload.
- **Adobe "Match Font" / Capture:** image-based, runs through Adobe's services.
- **fontTools metric comparison:** local. Compare x-height, cap height and character widths against measurements from screenshots; the width and height steps here already do this.
- **Learned font embeddings (DeepFont-style models):** local if you run the model yourself. Rank fonts by how close their renders sit to the crops in the model's feature space.

## Re-running

`python bakeoff.py` takes about 40 seconds. Options:

- `--fonts <@fontsource dir>`: where the font files are.
- `--gamma`: reruns the thin/thick check.
- `--boot N`: number of bootstrap resamples.
- `--no-sheet`: skips the contact sheet.

It needs numpy, scipy, scikit-image, Pillow, fonttools, uharfbuzz and freetype-py.

Files in this folder: `bakeoff.py`, `contact-sheet.png`, `results.json`, `results-all.csv`, `table.txt`, and a leftover progress log `err.log`.
