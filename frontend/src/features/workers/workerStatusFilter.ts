/** Filtro de estado del padrón. */
export type WorkerStatusFilter = 'active' | 'inactive' | 'all'

/** Se abre en activos: es a quien se busca casi siempre. */
export const WORKER_STATUS_FILTER_DEFAULT: WorkerStatusFilter = 'active'

// Todos va al final y no primero, como en otros filtros: acá el valor de partida es Activos.
export const WORKER_STATUS_FILTER_OPTIONS: ReadonlyArray<{
  value: WorkerStatusFilter
  label: string
}> = [
  { value: 'active', label: 'Activos' },
  { value: 'inactive', label: 'Inactivos' },
  { value: 'all', label: 'Todos' },
]

// "Todos" no manda el parámetro: con `isActive` ausente el backend no filtra.
const IS_ACTIVE_BY_FILTER: Record<WorkerStatusFilter, boolean | undefined> = {
  active: true,
  inactive: false,
  all: undefined,
}

/** El `isActive` que viaja al backend para cada filtro. */
export function isActiveParam(statusFilter: WorkerStatusFilter): boolean | undefined {
  return IS_ACTIVE_BY_FILTER[statusFilter]
}
