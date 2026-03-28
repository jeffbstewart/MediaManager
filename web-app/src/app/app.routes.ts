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
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./features/home/home').then(m => m.HomeComponent) },
    ],
  },
];
