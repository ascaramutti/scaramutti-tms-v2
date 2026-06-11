/** Query keys de catálogos (datos casi inmutables, cacheables agresivamente). */
export const catalogKeys = {
  all: ['catalogs'] as const,
  currencies: () => [...catalogKeys.all, 'currencies'] as const,
  paymentTerms: () => [...catalogKeys.all, 'payment-terms'] as const,
  serviceTypes: () => [...catalogKeys.all, 'service-types'] as const,
}
