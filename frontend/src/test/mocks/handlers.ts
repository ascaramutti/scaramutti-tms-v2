import type { HttpHandler } from 'msw'
import { authHandlers } from './handlers/auth'
import { cargoTypesHandlers } from './handlers/cargotypes'
import { catalogsHandlers } from './handlers/catalogs'
import { clientsHandlers } from './handlers/clients'
import { quotationsHandlers } from './handlers/quotations'

// Default handlers (happy path) por feature.
// Los tests individuales pueden overridear con `server.use(...)`.
// `catalogsHandlers` va ANTES de `quotationsHandlers`: `GET /quotations/config`
// debe matchear antes que `GET /quotations/:id`.
export const handlers: HttpHandler[] = [
  ...authHandlers,
  ...catalogsHandlers,
  ...clientsHandlers,
  ...cargoTypesHandlers,
  ...quotationsHandlers,
]
