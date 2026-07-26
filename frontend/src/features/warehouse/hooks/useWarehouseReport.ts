import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  getWarehouseReport,
  type GetWarehouseReportData,
  type WarehouseReportResponse,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'
import { isReportRangeValid, type ReportFilters } from '../schemas/report-filters.schema'

type WarehouseReportQuery = NonNullable<GetWarehouseReportData['query']>

/**
 * Los tres parámetros son obligatorios en el contrato, así que la query se arma
 * completa o no se dispara: no hay filtros opcionales que omitir.
 */
export function buildQuery(filters: ReportFilters): WarehouseReportQuery {
  return { cut: filters.cut, dateFrom: filters.dateFrom, dateTo: filters.dateTo }
}

async function fetchWarehouseReport(
  query: WarehouseReportQuery,
): Promise<WarehouseReportResponse> {
  const { data } = await getWarehouseReport({ query, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/reports')
  }
  return data
}

/**
 * Reporte agregado por corte y rango.
 *
 * El rango invertido se corta acá (`enabled: false`) en vez de dejar que el
 * backend responda 400 COM-001: mientras el usuario arrastra las fechas hay un
 * instante inválido, y un error rojo del servidor por algo que ya sabemos leer
 * en el cliente es peor que el aviso inline de la barra.
 *
 * `keepPreviousData` evita el parpadeo a spinner al cambiar de corte o de rango,
 * y el `staleTime` de un minuto refleja que es un agregado histórico, no un dato
 * que cambie a cada segundo.
 */
export function useWarehouseReport(filters: ReportFilters) {
  const query = buildQuery(filters)
  return useQuery({
    queryKey: warehouseKeys.report(query),
    queryFn: () => fetchWarehouseReport(query),
    enabled: isReportRangeValid(filters),
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  })
}
