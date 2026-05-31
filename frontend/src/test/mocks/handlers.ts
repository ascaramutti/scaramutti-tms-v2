import type { HttpHandler } from 'msw'
import { authHandlers } from './handlers/auth'
import { quotationsHandlers } from './handlers/quotations'

// Default handlers (happy path) por feature.
// Los tests individuales pueden overridear con `server.use(...)`.
export const handlers: HttpHandler[] = [
  ...authHandlers,
  ...quotationsHandlers,
]
