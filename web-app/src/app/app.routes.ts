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
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./features/home/home').then(m => m.HomeComponent) },
      { path: 'catalog', loadComponent: placeholder, data: { title: 'Catalog' } },
      { path: 'title/:titleId', loadComponent: placeholder, data: { title: 'Title Detail' } },
      { path: 'actor/:personId', loadComponent: placeholder, data: { title: 'Actor' } },
      { path: 'search', loadComponent: placeholder, data: { title: 'Search' } },
      { path: 'content/movies', loadComponent: placeholder, data: { title: 'Movies' } },
      { path: 'content/tv', loadComponent: placeholder, data: { title: 'TV Shows' } },
      { path: 'content/collections', loadComponent: placeholder, data: { title: 'Collections' } },
      { path: 'content/collection/:collectionId', loadComponent: placeholder, data: { title: 'Collection' } },
      { path: 'content/tags', loadComponent: placeholder, data: { title: 'Tags' } },
      { path: 'tag/:tagId', loadComponent: placeholder, data: { title: 'Tag' } },
      { path: 'content/family', loadComponent: placeholder, data: { title: 'Personal Videos' } },
      { path: 'wishlist', loadComponent: placeholder, data: { title: 'Wish List' } },
      { path: 'cameras', loadComponent: placeholder, data: { title: 'Cameras' } },
      { path: 'live-tv', loadComponent: placeholder, data: { title: 'Live TV' } },
      { path: 'profile', loadComponent: placeholder, data: { title: 'Profile' } },
      // TODO: Add adminGuard to gate admin routes by access_level
      { path: 'admin/add', loadComponent: placeholder, data: { title: 'Add Titles' } },
      { path: 'admin/transcodes', loadComponent: placeholder, data: { title: 'Transcodes' } },
      { path: 'admin/users', loadComponent: placeholder, data: { title: 'Users' } },
      { path: 'admin/settings', loadComponent: placeholder, data: { title: 'Settings' } },
      { path: 'admin/valuation', loadComponent: placeholder, data: { title: 'Valuation' } },
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
