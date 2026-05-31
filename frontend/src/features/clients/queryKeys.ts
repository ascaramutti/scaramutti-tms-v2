/** Query keys del dominio Clientes. */
export const clientKeys = {
  all: ['clients'] as const,
  searches: () => [...clientKeys.all, 'search'] as const,
  search: (q: string) => [...clientKeys.searches(), q] as const,
}
