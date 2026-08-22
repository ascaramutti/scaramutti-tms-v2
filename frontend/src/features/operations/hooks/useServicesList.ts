import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  listServices,
  type ListServicesData,
  type PageOfServiceSummary,
} from '../../../api'
import { operationsKeys } from '../queryKeys'
import {
  hasSearchableToken,
  isValidDateRange,
  type ServiceFilters,
} from '../schemas/service-filters.schema'

type ServiceListQuery = NonNullable<ListServicesData['query']>

interface UseServicesListParams {
  page: number
  size: number
  filters: ServiceFilters
}

/**
 * Construye el objeto de query del backend a partir de los filtros de UI. Los
 * filtros vacíos se OMITEN (no viajan como cadena vacía): el contrato los trata
 * igual, pero mandarlos ensucia la key del cache y crea entradas duplicadas para
 * el mismo resultado.
 *
 * El rango de fechas invertido no se manda: el contrato no lo rechaza, así que
 * viajaría para devolver una página vacía indistinguible de "no hay servicios".
 * La barra lo avisa; acá se lo omite hasta que vuelva a tener sentido.
 */
export function buildQuery({ page, size, filters }: UseServicesListParams): ServiceListQuery {
  const query: ServiceListQuery = { page, size }
  const trimmedQ = filters.q.trim()
  if (hasSearchableToken(trimmedQ)) query.q = trimmedQ
  if (filters.status) query.status = filters.status
  if (isValidDateRange(filters.dateFrom, filters.dateTo)) {
    if (filters.dateFrom) query.dateFrom = filters.dateFrom
    if (filters.dateTo) query.dateTo = filters.dateTo
  }
  return query
}

async function fetchServices(query: ServiceListQuery): Promise<PageOfServiceSummary> {
  const { data } = await listServices({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /services')
  }
  return data
}

/**
 * Lista los servicios, paginados y filtrados. `keepPreviousData` mantiene la tabla
 * anterior visible mientras se pagina o filtra (evita el parpadeo a spinner).
 *
 * Sin `staleTime`: un listado operativo no debe servir filas rancias al volver a la
 * pestaña, porque el estado de un viaje cambia durante el día.
 */
export function useServicesList(params: UseServicesListParams) {
  const query = buildQuery(params)
  return useQuery({
    queryKey: operationsKeys.serviceList(query),
    queryFn: () => fetchServices(query),
    placeholderData: keepPreviousData,
  })
}
