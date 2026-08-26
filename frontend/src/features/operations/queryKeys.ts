import type { ListServicesData } from '../../api'

type ServiceListParams = NonNullable<ListServicesData['query']>

/**
 * Query keys del dominio Operaciones. Factory centralizado para que las
 * invalidaciones y los caches usen siempre la misma forma de key.
 */
export const operationsKeys = {
  all: ['operations'] as const,
  services: () => [...operationsKeys.all, 'services'] as const,
  serviceLists: () => [...operationsKeys.services(), 'list'] as const,
  serviceList: (params: ServiceListParams) =>
    [...operationsKeys.serviceLists(), params] as const,
  serviceDetail: (id: number) => [...operationsKeys.services(), 'detail', id] as const,
  // Rama aparte de `serviceList`: los indicadores no aceptan parámetros y tienen
  // otra vida de cache, así que invalidar un listado filtrado no debe tirar abajo
  // los contadores (ni al revés).
  serviceStats: () => [...operationsKeys.services(), 'stats'] as const,
  // Conductores (`public.drivers`). Cuelga del módulo y no de `shared/catalogs`
  // porque hoy lo pide solo operaciones: la flota se mudó a compartida cuando la
  // pidió un segundo módulo, y este catálogo todavía tiene uno.
  drivers: () => [...operationsKeys.all, 'drivers'] as const,
}
