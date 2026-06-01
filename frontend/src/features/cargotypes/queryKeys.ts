/** Query keys del dominio Tipos de carga. */
export const cargoTypeKeys = {
  all: ['cargo-types'] as const,
  searches: () => [...cargoTypeKeys.all, 'search'] as const,
  search: (q: string) => [...cargoTypeKeys.searches(), q] as const,
}
