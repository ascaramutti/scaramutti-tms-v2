import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './shared/auth/ProtectedRoute'
import { AppLayout } from './shared/layout/AppLayout'
import { LoginPage } from './features/auth/components/LoginPage'
import { ChangePasswordPage } from './features/auth/components/ChangePasswordPage'
import { CotizacionesListPage } from './features/quotations/pages/CotizacionesListPage'
import { CotizacionDetailPage } from './features/quotations/pages/CotizacionDetailPage'
import { CotizacionEditPage } from './features/quotations/pages/CotizacionEditPage'
import { CotizacionWizardPage } from './features/quotations/pages/CotizacionWizardPage'

// Toda la app vive bajo /cotizaciones (coincide con el `base` de Vite): v2 convive
// con v1 detrás de un gateway que rutea por prefijo. No usamos `basename` porque
// las rutas del módulo ya traían el prefijo /cotizaciones — solo login y cuenta
// se movieron adentro. La raíz `/` del dominio pertenece a v1.
export const router = createBrowserRouter([
  { path: '/cotizaciones/login', element: <LoginPage /> },
  {
    // Layout route: las rutas autenticadas comparten AppLayout (con sidebar).
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      {
        path: '/cotizaciones',
        element: (
          <ProtectedRoute allowedRoles={['admin', 'sales', 'general_manager', 'operations_manager']}>
            <CotizacionesListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /cotizaciones/:id para que "nueva" no matchee como id.
      {
        path: '/cotizaciones/nueva',
        element: (
          <ProtectedRoute allowedRoles={['admin', 'sales', 'general_manager', 'operations_manager']}>
            <CotizacionWizardPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /cotizaciones/:id para que "editar" no matchee como id.
      {
        path: '/cotizaciones/:id/editar',
        element: (
          <ProtectedRoute allowedRoles={['admin', 'sales', 'general_manager', 'operations_manager']}>
            <CotizacionEditPage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/:id',
        element: (
          <ProtectedRoute allowedRoles={['admin', 'sales', 'general_manager', 'operations_manager']}>
            <CotizacionDetailPage />
          </ProtectedRoute>
        ),
      },
      { path: '/cotizaciones/cuenta/cambiar-contrasena', element: <ChangePasswordPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/cotizaciones" replace /> },
])
