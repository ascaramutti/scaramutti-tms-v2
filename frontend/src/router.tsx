import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './shared/auth/ProtectedRoute'
import { QUOTATION_ROLES, WAREHOUSE_ROLES } from './shared/auth/moduleRoles'
import { AppLayout } from './shared/layout/AppLayout'
import { LoginPage } from './features/auth/components/LoginPage'
import { ChangePasswordPage } from './features/auth/components/ChangePasswordPage'
import { CotizacionesListPage } from './features/quotations/pages/CotizacionesListPage'
import { CotizacionDetailPage } from './features/quotations/pages/CotizacionDetailPage'
import { CotizacionEditPage } from './features/quotations/pages/CotizacionEditPage'
import { CotizacionWizardPage } from './features/quotations/pages/CotizacionWizardPage'
import { StockListPage } from './features/warehouse/pages/StockListPage'

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
          <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
            <CotizacionesListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /cotizaciones/:id para que "nueva" no matchee como id.
      {
        path: '/cotizaciones/nueva',
        element: (
          <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
            <CotizacionWizardPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /cotizaciones/:id para que "editar" no matchee como id.
      {
        path: '/cotizaciones/:id/editar',
        element: (
          <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
            <CotizacionEditPage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/:id',
        element: (
          <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
            <CotizacionDetailPage />
          </ProtectedRoute>
        ),
      },
      // Módulo Almacén. Cuelga del mismo prefijo porque /cotizaciones es el
      // `base` de Vite (la SPA entera se sirve ahí), no el módulo comercial:
      // así el gateway sigue ruteando v2 por un único prefijo.
      {
        path: '/cotizaciones/almacen',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <StockListPage />
          </ProtectedRoute>
        ),
      },
      { path: '/cotizaciones/cuenta/cambiar-contrasena', element: <ChangePasswordPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/cotizaciones" replace /> },
])
