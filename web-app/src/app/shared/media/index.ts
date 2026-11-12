// Shared presentational media components (docs/plans/web-theming.md,
// decision 5). Each is OnPush with signal inputs and reads only layout
// and theme tokens, so every theme renders the same geometry.
export { artHue, artInitial } from './art-hue';
export { CategoryTileComponent } from './category-tile';
export { ChipRowComponent, type ChipLink } from './chip-row';
export { FaqAccordionComponent, type FaqEntry } from './faq-accordion';
export { HeroBannerComponent } from './hero-banner';
export { HeroCarouselComponent } from './hero-carousel';
export { MediaRowComponent } from './media-row';
export { PersonCardComponent } from './person-card';
export { PlaceholderArtComponent } from './placeholder-art';
export { PosterCardComponent, type CardLink } from './poster-card';
export { RowScrollDirective } from './row-scroll';
export { SectionHeadComponent } from './section-head';
export { WideCardComponent } from './wide-card';
