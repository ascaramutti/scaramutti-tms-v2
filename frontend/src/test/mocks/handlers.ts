import type { HttpHandler } from 'msw'
import { authHandlers } from './handlers/auth'

// Default handlers (happy path) por feature.
// Los tests individuales pueden overridear con `server.use(...)`.
export const handlers: HttpHandler[] = [
  ...authHandlers,
]
