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
    ],
  },
  { path: '**', redirectTo: '' },
];
