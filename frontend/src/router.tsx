import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './shared/auth/ProtectedRoute'
import { AppLayout } from './shared/layout/AppLayout'
import { LoginPage } from './features/auth/components/LoginPage'
import { ChangePasswordPage } from './features/auth/components/ChangePasswordPage'
import { CotizacionesListPage } from './features/quotations/pages/CotizacionesListPage'
import { CotizacionDetailPage } from './features/quotations/pages/CotizacionDetailPage'
import { HomePage } from './pages/HomePage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    // Layout route: las rutas autenticadas comparten AppLayout (con sidebar).
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      { path: '/', element: <HomePage /> },
      {
        path: '/cotizaciones',
        element: (
          <ProtectedRoute allowedRoles={['admin', 'sales', 'general_manager', 'operations_manager']}>
            <CotizacionesListPage />
          </ProtectedRoute>
        ),
      },
      // El wizard de creación aún no existe; hasta entonces /cotizaciones/nueva
      // redirige a Inicio (evita que matchee /:id como id="nueva"). Reemplazar
      // por el wizard cuando se implemente.
      { path: '/cotizaciones/nueva', element: <Navigate to="/" replace /> },
      {
        path: '/cotizaciones/:id',
        element: (
          <ProtectedRoute allowedRoles={['admin', 'sales', 'general_manager', 'operations_manager']}>
            <CotizacionDetailPage />
          </ProtectedRoute>
        ),
      },
      { path: '/cuenta/cambiar-contrasena', element: <ChangePasswordPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
