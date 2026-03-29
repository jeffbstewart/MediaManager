import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { setupGuard } from './core/setup.guard';

const placeholder = () => import('./features/placeholder/placeholder').then(m => m.PlaceholderComponent);

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login').then(m => m.LoginComponent),
  },
  {
    path: 'setup',
    canActivate: [setupGuard],
    loadComponent: () => import('./features/auth/setup').then(m => m.SetupComponent),
  },
  {
    path: 'play/:transcodeId',
    canActivate: [authGuard],
    loadComponent: () => import('./features/player/player').then(m => m.PlayerComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./core/shell/shell').then(m => m.ShellComponent),
    children: [
      { path: '', loadComponent: () => import('./features/home/home').then(m => m.HomeComponent) },
      { path: 'catalog', loadComponent: placeholder, data: { title: 'Catalog' } },
      { path: 'title/:titleId', loadComponent: () => import('./features/title-detail/title-detail').then(m => m.TitleDetailComponent) },
      { path: 'actor/:personId', loadComponent: placeholder, data: { title: 'Actor' } },
      { path: 'search', loadComponent: placeholder, data: { title: 'Search' } },
      { path: 'content/movies', loadComponent: () => import('./features/content/movies').then(m => m.MoviesComponent) },
      { path: 'content/tv', loadComponent: () => import('./features/content/tv-shows').then(m => m.TvShowsComponent) },
      { path: 'content/collections', loadComponent: () => import('./features/content/collections').then(m => m.CollectionsComponent) },
      { path: 'content/collection/:collectionId', loadComponent: () => import('./features/content/collection-detail').then(m => m.CollectionDetailComponent) },
      { path: 'content/tags', loadComponent: () => import('./features/content/tags').then(m => m.TagsComponent) },
      { path: 'tag/:tagId', loadComponent: () => import('./features/content/tag-detail').then(m => m.TagDetailComponent) },
      { path: 'content/family', loadComponent: placeholder, data: { title: 'Personal Videos' } },
      { path: 'wishlist', loadComponent: placeholder, data: { title: 'Wish List' } },
      { path: 'cameras', loadComponent: placeholder, data: { title: 'Cameras' } },
      { path: 'live-tv', loadComponent: placeholder, data: { title: 'Live TV' } },
      { path: 'profile', loadComponent: placeholder, data: { title: 'Profile' } },
      { path: 'help', loadComponent: placeholder, data: { title: 'Help' } },
      // TODO: Add adminGuard to gate admin routes by access_level
      { path: 'admin/add', loadComponent: placeholder, data: { title: 'Add Titles' } },
      { path: 'admin/transcodes', loadComponent: placeholder, data: { title: 'Transcodes' } },
      { path: 'admin/transcodes/status', loadComponent: placeholder, data: { title: 'Transcode Status' } },
      { path: 'admin/transcodes/unmatched', loadComponent: placeholder, data: { title: 'Unmatched Transcodes' } },
      { path: 'admin/transcodes/linked', loadComponent: placeholder, data: { title: 'Linked Transcodes' } },
      { path: 'admin/transcodes/backlog', loadComponent: placeholder, data: { title: 'Transcode Backlog' } },
      { path: 'admin/users', loadComponent: placeholder, data: { title: 'Users' } },
      { path: 'admin/settings', loadComponent: placeholder, data: { title: 'Settings' } },
      { path: 'admin/valuation', loadComponent: placeholder, data: { title: 'Valuation' } },
      { path: 'admin/purchase-wishes', loadComponent: placeholder, data: { title: 'User Wishes' } },
      { path: 'admin/data-quality', loadComponent: placeholder, data: { title: 'Data Quality' } },
      { path: 'admin/inventory', loadComponent: placeholder, data: { title: 'Inventory Report' } },
      { path: 'admin/sessions', loadComponent: placeholder, data: { title: 'Active Sessions' } },
      { path: 'admin/cameras', loadComponent: placeholder, data: { title: 'Camera Settings' } },
      { path: 'admin/live-tv', loadComponent: placeholder, data: { title: 'Live TV Settings' } },
      { path: 'admin/tags', loadComponent: placeholder, data: { title: 'Tag Management' } },
      { path: 'admin/import', loadComponent: placeholder, data: { title: 'Amazon Import' } },
      { path: 'admin/expand', loadComponent: placeholder, data: { title: 'Expand' } },
      { path: 'admin/document-ownership', loadComponent: placeholder, data: { title: 'Document Ownership' } },
    ],
  },
];
