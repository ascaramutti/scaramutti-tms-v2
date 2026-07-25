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
import { ProductDetailPage } from './features/warehouse/pages/ProductDetailPage'
import { EntriesListPage } from './features/warehouse/pages/EntriesListPage'
import { EntryCreatePage } from './features/warehouse/pages/EntryCreatePage'
import { EntryDetailPage } from './features/warehouse/pages/EntryDetailPage'
import { EntryEditPage } from './features/warehouse/pages/EntryEditPage'
import { WithdrawalsListPage } from './features/warehouse/pages/WithdrawalsListPage'
import { WithdrawalCreatePage } from './features/warehouse/pages/WithdrawalCreatePage'
import { WithdrawalDetailPage } from './features/warehouse/pages/WithdrawalDetailPage'
import { WithdrawalEditPage } from './features/warehouse/pages/WithdrawalEditPage'

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
      // Declarado ANTES de /entradas/:id para que "nueva" no matchee como id.
      {
        path: '/cotizaciones/almacen/entradas/nueva',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntryCreatePage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/almacen/entradas',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntriesListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /entradas/:id para que "editar" no matchee como id.
      {
        path: '/cotizaciones/almacen/entradas/:id/editar',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntryEditPage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/almacen/entradas/:id',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntryDetailPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /retiros/:id para que "nuevo" no matchee como id.
      {
        path: '/cotizaciones/almacen/retiros/nuevo',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalCreatePage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/almacen/retiros',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalsListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /retiros/:id para que "editar" no matchee como id.
      {
        path: '/cotizaciones/almacen/retiros/:id/editar',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalEditPage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/almacen/retiros/:id',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalDetailPage />
          </ProtectedRoute>
        ),
      },
      {
        path: '/cotizaciones/almacen/productos/:id',
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <ProductDetailPage />
          </ProtectedRoute>
        ),
      },
      { path: '/cotizaciones/cuenta/cambiar-contrasena', element: <ChangePasswordPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/cotizaciones" replace /> },
])
