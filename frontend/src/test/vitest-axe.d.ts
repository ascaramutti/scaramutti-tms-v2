import 'vitest'
import type { AxeMatchers } from 'vitest-axe/matchers'

// vitest-axe 0.1.0 augmenta el namespace global `Vi` (estilo viejo), que vitest 3 ya no usa para
// `expect(x)`. Re-augmentamos el módulo `vitest` para que `toHaveNoViolations()` tipe correctamente.
// Las interfaces vacías son intencionales (solo heredan los matchers de axe).
declare module 'vitest' {
  /* eslint-disable @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars */
  interface Assertion<T = unknown> extends AxeMatchers {}
  interface AsymmetricMatchersContaining extends AxeMatchers {}
  /* eslint-enable @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars */
}
