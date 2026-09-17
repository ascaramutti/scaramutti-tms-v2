/** Parámetros que distinguen una búsqueda de clientes de otra. */
export type ClientSearchParams = {
  q: string
  /** Presente solo donde la pantalla filtra; los combobox no lo mandan. */
  isActive?: boolean
}

/** Query keys del dominio Clientes. */
export const clientKeys = {
  all: ['clients'] as const,
  searches: () => [...clientKeys.all, 'search'] as const,
  search: (params: ClientSearchParams) => [...clientKeys.searches(), params] as const,
  detail: (id: number) => [...clientKeys.all, 'detail', id] as const,
}
