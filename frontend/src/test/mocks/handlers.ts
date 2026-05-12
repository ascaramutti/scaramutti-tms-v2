import type { HttpHandler } from 'msw'

// Los handlers especificos por feature se agregan aca o se importan desde
// `src/features/<feature>/mocks/*.ts`. Los tests individuales pueden hacer
// `server.use(...)` para override puntual.
export const handlers: HttpHandler[] = []
