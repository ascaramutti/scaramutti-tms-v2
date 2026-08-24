import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { useAuth } from '../../../shared/auth/AuthContext'
import { OPERACIONES_LANDING } from '../../../shared/auth/roleLanding'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { useLastGoodPage } from '../../../shared/hooks/useLastGoodPage'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { ServicesFilterBar } from '../components/ServicesFilterBar'
import { ServicesKpiStrip } from '../components/ServicesKpiStrip'
import { ServicesTable } from '../components/ServicesTable'
import { useServicesList } from '../hooks/useServicesList'
import { useServiceStats } from '../hooks/useServiceStats'
import {
  EMPTY_SERVICE_FILTERS,
  type ServiceFilters,
} from '../schemas/service-filters.schema'
import { canCreateService, canSeeServicePrices } from '../status/operationsPermissions'

/** Filas por página. El contrato admite hasta 100; 10 es lo que usan los listados del sistema. */
const PAGE_SIZE = 10

/** Solo la búsqueda libre se debouncea; el resto de los filtros aplica al instante. */
const SEARCH_DEBOUNCE_MS = 300

/**
 * Listado de servicios del módulo Operaciones, con la tira de indicadores del
 * tablero operativo.
 *
 * Los indicadores y el listado son dos consultas independientes y se degradan por
 * separado: el strip es contexto y la tabla es la tarea, así que un fallo de los
 * contadores no puede dejar al despacho sin ver sus viajes (ni al revés).
 */
export function ServicesListPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [filters, setFilters] = useState<ServiceFilters>(EMPTY_SERVICE_FILTERS)
  const [page, setPage] = useState(0)

  const debouncedQ = useDebouncedValue(filters.q, SEARCH_DEBOUNCE_MS)
  const effectiveFilters = useMemo<ServiceFilters>(
    () => ({ ...filters, q: debouncedQ }),
    [filters, debouncedQ],
  )

  const servicesQuery = useServicesList({ page, size: PAGE_SIZE, filters: effectiveFilters })
  const statsQuery = useServiceStats()

  const shownPage = useLastGoodPage(servicesQuery.data)

  const hasActiveFilters =
    filters.q.trim().length > 0 ||
    filters.status !== undefined ||
    filters.dateFrom !== undefined ||
    filters.dateTo !== undefined

  function handleFiltersChange(next: ServiceFilters) {
    setFilters(next)
    // Cambiar un filtro reordena el conjunto entero: quedarse en la página 5 de
    // un resultado que ahora tiene dos páginas mostraría una tabla vacía.
    setPage(0)
  }

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Servicios"
        description="Control de viajes · estado y recursos asignados de cada servicio."
        divider
        action={
          // Sin botón para el despacho: el alta exige el precio, que no puede ver,
          // así que el camino terminaría en un 403 del servidor.
          canCreateService(user?.role) ? (
            <button
              type="button"
              onClick={() => navigate(`${OPERACIONES_LANDING}/servicios/nuevo`)}
              className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
            >
              <Plus className="h-4 w-4" aria-hidden="true" />
              Nuevo servicio
            </button>
          ) : undefined
        }
      />

      <ServicesKpiStrip
        data={statsQuery.data}
        isLoading={statsQuery.isLoading}
        isError={statsQuery.isError}
        errorMessage={
          statsQuery.isError
            ? getApiErrorMessage(
                statsQuery.error,
                'No se pudieron cargar los indicadores operativos.',
              )
            : undefined
        }
        onRetry={() => void statsQuery.refetch()}
      />

      <ServicesFilterBar value={filters} onChange={handleFiltersChange} />

      <ServicesTable
        data={shownPage?.content ?? []}
        page={shownPage?.page ?? page}
        size={PAGE_SIZE}
        total={shownPage?.totalElements ?? 0}
        totalPages={shownPage?.totalPages ?? 0}
        onPageChange={setPage}
        isLoading={servicesQuery.isLoading}
        isFetching={servicesQuery.isFetching}
        isError={servicesQuery.isError}
        errorMessage={
          servicesQuery.isError
            ? getApiErrorMessage(servicesQuery.error, 'No se pudieron cargar los servicios.')
            : undefined
        }
        onRetry={() => void servicesQuery.refetch()}
        showPrice={canSeeServicePrices(user?.role)}
        hasActiveFilters={hasActiveFilters}
      />
    </div>
  )
}
