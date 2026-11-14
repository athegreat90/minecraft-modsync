import { Routes } from '@angular/router';
import { sessionGuard } from './core/auth/session.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/authentication/login').then((m) => m.Login),
  },
  {
    path: '',
    canActivate: [sessionGuard],
    loadComponent: () => import('./shared/layouts/shell').then((m) => m.Shell),
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () => import('./features/dashboard/overview').then((m) => m.Overview),
      },
      {
        path: 'servers/:id',
        loadComponent: () => import('./features/servers/server-page').then((m) => m.ServerPageView),
      },
      {
        path: 'users',
        loadComponent: () => import('./features/users/users-page').then((m) => m.UsersPage),
      },
      {
        path: 'users/new',
        loadComponent: () => import('./features/users/user-new').then((m) => m.UserNew),
      },
      {
        path: 'users/:id',
        loadComponent: () => import('./features/users/user-detail').then((m) => m.UserDetailView),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
