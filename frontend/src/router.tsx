import { CHANGE_PASSWORD_PATH, LOGIN_PATH, QUOTATIONS_BASE, WAREHOUSE_BASE } from './shared/paths'
import { createBrowserRouter, type RouteObject } from 'react-router-dom'
import { LandingRedirect } from './shared/auth/LandingRedirect'
import { ProtectedRoute } from './shared/auth/ProtectedRoute'
import { RequireNumericId } from './shared/auth/RequireNumericId'
import {
  OPERATIONS_ROLES,
  QUOTATION_ROLES,
  SERVICE_PRICE_WRITE_ROLES,
  WAREHOUSE_ROLES,
} from './shared/auth/moduleRoles'
import { OPERACIONES_LANDING } from './shared/auth/roleLanding'
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
import { WarehouseReportsPage } from './features/warehouse/pages/WarehouseReportsPage'
import { OpeningBalancesPage } from './features/warehouse/pages/OpeningBalancesPage'
import { ServicesListPage } from './features/operations/pages/ServicesListPage'
import { ServiceDetailPage } from './features/operations/pages/ServiceDetailPage'
import { ServiceEditPage } from './features/operations/pages/ServiceEditPage'
import { ServiceCreatePage } from './features/operations/pages/ServiceCreatePage'

// La aplicación se sirve desde la raíz del dominio. Hasta la mudanza de 2026-09
// vivía bajo un prefijo heredado de cuando convivía con la v1 detrás de un
// gateway que ruteaba por prefijo; retirada la v1, el prefijo dejó de tener
// sentido. No se usa `basename`: con la base en la raíz no hace falta. Los
// valores viven en shared/paths.
/**
 * La tabla de rutas se exporta aparte del router para poder montarla en un
 * router de memoria desde los tests: sin eso, cada test que necesita una ruta
 * declara la suya propia y nadie verifica la de verdad (un rol equivocado o un
 * typo en el path pasan a producción con la suite en verde).
 */
export const routes: RouteObject[] = [
  { path: LOGIN_PATH, element: <LoginPage /> },
  {
    // Layout route: las rutas autenticadas comparten AppLayout (con sidebar).
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      {
        path: QUOTATIONS_BASE,
        element: (
          <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
            <CotizacionesListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES del detalle por id para que "nueva" no matchee como id.
      {
        path: `${QUOTATIONS_BASE}/nueva`,
        element: (
          <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
            <CotizacionWizardPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES del detalle por id para que "editar" no matchee como id.
      {
        path: `${QUOTATIONS_BASE}/:id/editar`,
        element: (
          // La validación del id va ANTES de la guarda de rol: ver RequireNumericId.
          <RequireNumericId>
            <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
              <CotizacionEditPage />
            </ProtectedRoute>
          </RequireNumericId>
        ),
      },
      {
        path: `${QUOTATIONS_BASE}/:id`,
        element: (
          // La validación del id va ANTES de la guarda de rol: ver RequireNumericId.
          <RequireNumericId>
            <ProtectedRoute allowedRoles={QUOTATION_ROLES} moduleName="Cotizaciones">
              <CotizacionDetailPage />
            </ProtectedRoute>
          </RequireNumericId>
        ),
      },
      // Módulo Almacén, con su propia raíz. Hasta la mudanza de 2026-09 colgaba
      // del prefijo que se llamaba como el módulo comercial, y por eso la URL
      // decía "cotizaciones" delante de almacén.
      {
        path: WAREHOUSE_BASE,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <StockListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /entradas/:id para que "nueva" no matchee como id.
      {
        path: `${WAREHOUSE_BASE}/entradas/nueva`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntryCreatePage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/entradas`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntriesListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /entradas/:id para que "editar" no matchee como id.
      {
        path: `${WAREHOUSE_BASE}/entradas/:id/editar`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntryEditPage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/entradas/:id`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <EntryDetailPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /retiros/:id para que "nuevo" no matchee como id.
      {
        path: `${WAREHOUSE_BASE}/retiros/nuevo`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalCreatePage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/retiros`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalsListPage />
          </ProtectedRoute>
        ),
      },
      // Declarado ANTES de /retiros/:id para que "editar" no matchee como id.
      {
        path: `${WAREHOUSE_BASE}/retiros/:id/editar`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalEditPage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/retiros/:id`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WithdrawalDetailPage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/reportes`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <WarehouseReportsPage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/corte-inicial`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <OpeningBalancesPage />
          </ProtectedRoute>
        ),
      },
      {
        path: `${WAREHOUSE_BASE}/productos/:id`,
        element: (
          <ProtectedRoute allowedRoles={WAREHOUSE_ROLES} moduleName="Almacén">
            <ProductDetailPage />
          </ProtectedRoute>
        ),
      },
      // Módulo Operaciones (control de viajes), con su propia raíz, por la misma
      // historia que almacén.
      {
        path: OPERACIONES_LANDING,
        element: (
          <ProtectedRoute allowedRoles={OPERATIONS_ROLES} moduleName="Operaciones">
            <ServicesListPage />
          </ProtectedRoute>
        ),
      },
      {
        // Roles propios: el alta obliga a mandar el precio, así que el despacho
        // queda afuera aunque el resto del módulo sea suyo.
        path: `${OPERACIONES_LANDING}/servicios/nuevo`,
        element: (
          <ProtectedRoute allowedRoles={SERVICE_PRICE_WRITE_ROLES} actionName="registrar un servicio">
            <ServiceCreatePage />
          </ProtectedRoute>
        ),
      },
      {
        // Roles propios, igual que el alta y por la misma razón: el cuerpo de la edición
        // obliga a mandar el precio, que es justo lo que al despacho se le oculta, así
        // que ese rol recibiría un 403. Va ANTES de `:id` por legibilidad, igual que el
        // alta: react-router rankea por especificidad y `editar` no es un id válido, pero
        // el archivo se lee de arriba abajo.
        path: `${OPERACIONES_LANDING}/servicios/:id/editar`,
        element: (
          <ProtectedRoute allowedRoles={SERVICE_PRICE_WRITE_ROLES} actionName="editar un servicio">
            <ServiceEditPage />
          </ProtectedRoute>
        ),
      },
      {
        // Los cinco roles del módulo: el detalle se LEE, y el despacho lo lee sin
        // los importes porque el servidor se los omite (RN-OP8). Va DESPUÉS del
        // alta: react-router rankea por especificidad y la ruta estática gana
        // igual, pero leído de arriba abajo el archivo no invita a dudarlo. Hay
        // un test que lo fija.
        path: `${OPERACIONES_LANDING}/servicios/:id`,
        element: (
          <ProtectedRoute allowedRoles={OPERATIONS_ROLES} moduleName="Operaciones">
            <ServiceDetailPage />
          </ProtectedRoute>
        ),
      },
      { path: CHANGE_PASSWORD_PATH, element: <ChangePasswordPage /> },
    ],
  },
  // La raíz del dominio. Antes la resolvía nginx con un 302 al prefijo; desde que
  // la SPA es la raíz, entra acá y aterriza según el rol, o manda al login si no
  // hay sesión: lo mismo que decide el comodín de abajo.
  { path: '/', element: <LandingRedirect /> },
  // Cualquier ruta que no existe: decide según la sesión (ver LandingRedirect).
  { path: '*', element: <LandingRedirect /> },
]

export const router = createBrowserRouter(routes)
