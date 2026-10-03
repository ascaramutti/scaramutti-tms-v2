/** Parámetros que distinguen una búsqueda del padrón de otra. */
export type WorkerSearchParams = {
  q: string
  /** Ausente cuando el filtro es "Todos": el backend devuelve activos e inactivos. */
  isActive?: boolean
}

/**
 * Query keys del padrón de trabajadores. Propias y no las de almacén: su combobox
 * busca solo activos y el padrón filtra por estado, así que compartir la entrada
 * le mostraría inactivos a quien elige quién recibe un retiro.
 */
export const workerKeys = {
  all: ['workers'] as const,
  searches: () => [...workerKeys.all, 'search'] as const,
  search: (params: WorkerSearchParams) => [...workerKeys.searches(), params] as const,
  detail: (id: number) => [...workerKeys.all, 'detail', id] as const,
  roles: () => [...workerKeys.all, 'roles'] as const,
  documentTypes: () => [...workerKeys.all, 'document-types'] as const,
  /** Clave de la mutación del alta, para que la página sepa si hay un envío en vuelo. */
  create: () => [...workerKeys.all, 'create'] as const,
  /** Clave de la mutación de la edición, por la misma razón. */
  update: (id: number) => [...workerKeys.all, 'update', id] as const,
}
