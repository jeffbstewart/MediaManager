import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { setupGuard } from './core/setup.guard';

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
    path: 'change-password',
    canActivate: [authGuard],
    loadComponent: () => import('./features/auth/change-password').then(m => m.ChangePasswordComponent),
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
      { path: 'title/:titleId', loadComponent: () => import('./features/title-detail/title-detail').then(m => m.TitleDetailComponent) },
      { path: 'actor/:personId', loadComponent: () => import('./features/actor/actor').then(m => m.ActorComponent) },
      { path: 'search', loadComponent: () => import('./features/search/search').then(m => m.SearchComponent) },
      { path: 'content/movies', loadComponent: () => import('./features/content/movies').then(m => m.MoviesComponent) },
      { path: 'content/tv', loadComponent: () => import('./features/content/tv-shows').then(m => m.TvShowsComponent) },
      { path: 'content/collections', loadComponent: () => import('./features/content/collections').then(m => m.CollectionsComponent) },
      { path: 'content/collection/:collectionId', loadComponent: () => import('./features/content/collection-detail').then(m => m.CollectionDetailComponent) },
      { path: 'content/tags', loadComponent: () => import('./features/content/tags').then(m => m.TagsComponent) },
      { path: 'tag/:tagId', loadComponent: () => import('./features/content/tag-detail').then(m => m.TagDetailComponent) },
      { path: 'content/family', loadComponent: () => import('./features/content/personal-videos').then(m => m.PersonalVideosComponent) },
      { path: 'wishlist', loadComponent: () => import('./features/wishlist/wishlist').then(m => m.WishListComponent) },
      { path: 'cameras', loadComponent: () => import('./features/cameras/cameras').then(m => m.CamerasComponent) },
      { path: 'live-tv', loadComponent: () => import('./features/live-tv/live-tv').then(m => m.LiveTvComponent) },
      { path: 'live-tv/:channelId', loadComponent: () => import('./features/live-tv/live-tv-player').then(m => m.LiveTvPlayerComponent) },
      { path: 'profile', loadComponent: () => import('./features/profile/profile').then(m => m.ProfileComponent) },
      { path: 'help', loadComponent: () => import('./features/help/help').then(m => m.HelpComponent) },
      // TODO: Add adminGuard to gate admin routes by access_level
      { path: 'admin/add', loadComponent: () => import('./features/admin/add-item').then(m => m.AddItemComponent) },
      { path: 'admin/item/:mediaItemId', loadComponent: () => import('./features/admin/media-item-edit').then(m => m.MediaItemEditComponent) },
      { path: 'admin/transcodes/status', loadComponent: () => import('./features/admin/transcode-status').then(m => m.TranscodeStatusComponent) },
      { path: 'admin/transcodes/unmatched', loadComponent: () => import('./features/admin/transcode-unmatched').then(m => m.TranscodeUnmatchedComponent) },
      { path: 'admin/transcodes/linked', loadComponent: () => import('./features/admin/transcode-linked').then(m => m.TranscodeLinkedComponent) },
      { path: 'admin/transcodes/backlog', loadComponent: () => import('./features/admin/transcode-backlog').then(m => m.TranscodeBacklogComponent) },
      { path: 'admin/users', loadComponent: () => import('./features/admin/users').then(m => m.UsersComponent) },
      { path: 'admin/settings', loadComponent: () => import('./features/admin/settings').then(m => m.SettingsComponent) },
      { path: 'admin/valuation', loadComponent: () => import('./features/admin/valuation').then(m => m.ValuationComponent) },
      { path: 'admin/purchase-wishes', loadComponent: () => import('./features/admin/purchase-wishes').then(m => m.PurchaseWishesComponent) },
      { path: 'admin/import', loadComponent: () => import('./features/admin/amazon-import').then(m => m.AmazonImportComponent) },
      { path: 'admin/data-quality', loadComponent: () => import('./features/admin/data-quality').then(m => m.DataQualityComponent) },
      { path: 'admin/inventory', loadComponent: () => import('./features/admin/inventory-report').then(m => m.InventoryReportComponent) },
      { path: 'admin/cameras', loadComponent: () => import('./features/admin/camera-settings').then(m => m.CameraSettingsComponent) },
      { path: 'admin/live-tv', loadComponent: () => import('./features/admin/live-tv-settings').then(m => m.LiveTvSettingsComponent) },
      { path: 'admin/tags', loadComponent: () => import('./features/admin/tag-management').then(m => m.TagManagementComponent) },
      { path: 'admin/import', loadComponent: () => import('./features/admin/amazon-import').then(m => m.AmazonImportComponent) },
      { path: 'admin/expand', loadComponent: () => import('./features/admin/expand').then(m => m.ExpandComponent) },
      { path: 'admin/document-ownership', loadComponent: () => import('./features/admin/document-ownership').then(m => m.DocumentOwnershipComponent) },
    ],
  },
];
