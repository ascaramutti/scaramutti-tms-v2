import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { useLastGoodPage } from '../../../shared/hooks/useLastGoodPage'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { StockFilterBar } from '../components/StockFilterBar'
import { StockTable } from '../components/StockTable'
import { WarehouseKpiStrip } from '../components/WarehouseKpiStrip'
import { SEARCH_MIN_LENGTH, useWarehouseProductsList } from '../hooks/useWarehouseProductsList'
import { useWarehouseProductCategories } from '../hooks/useWarehouseProductCategories'
import { useWarehouseStats } from '../hooks/useWarehouseStats'
import { EMPTY_STOCK_FILTERS, type StockFilters } from '../schemas/stock-filters.schema'

const PAGE_SIZE = 10
const SEARCH_DEBOUNCE_MS = 300

/**
 * Existencias: portada del módulo Almacén. Indicadores del almacén arriba y el
 * inventario vigente (solo productos activos) con su stock abajo.
 *
 * Las dos consultas son independientes a propósito: si los indicadores fallan,
 * la tabla sigue trabajando, y al revés. Son datos distintos y ninguno depende
 * del otro para tener sentido.
 */
export function StockListPage() {
  const navigate = useNavigate()
  const [filters, setFilters] = useState<StockFilters>(EMPTY_STOCK_FILTERS)
  const [page, setPage] = useState(0)

  // Solo la búsqueda libre se debouncea; el select y el corte aplican al instante.
  const debouncedQ = useDebouncedValue(filters.q, SEARCH_DEBOUNCE_MS)
  const effectiveFilters = useMemo<StockFilters>(
    () => ({ ...filters, q: debouncedQ }),
    [filters, debouncedQ],
  )

  const { data, isLoading, isFetching, isError, error, refetch } = useWarehouseProductsList({
    page,
    size: PAGE_SIZE,
    filters: effectiveFilters,
  })
  const stats = useWarehouseStats()
  const categories = useWarehouseProductCategories()

  const shownPage = useLastGoodPage(data)

  function handleFiltersChange(next: StockFilters) {
    setFilters(next)
    setPage(0) // cualquier cambio de filtro reinicia a la primera página
  }

  function handleToggleLowOnly() {
    handleFiltersChange({ ...filters, lowOnly: !filters.lowOnly })
  }

  const hasActiveFilters =
    effectiveFilters.q.trim().length >= SEARCH_MIN_LENGTH ||
    !!effectiveFilters.categoryId ||
    effectiveFilters.lowOnly

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Existencias"
        description="Inventario del almacén · el stock se calcula del corte inicial, las entradas y los retiros."
        divider
      />

      <WarehouseKpiStrip
        data={stats.data}
        isLoading={stats.isLoading}
        isError={stats.isError}
        errorMessage={getApiErrorMessage(
          stats.error,
          'No se pudieron cargar los indicadores del almacén.',
        )}
        onRetry={() => stats.refetch()}
        lowOnly={filters.lowOnly}
        onToggleLowOnly={handleToggleLowOnly}
      />

      <StockFilterBar
        value={filters}
        onChange={handleFiltersChange}
        categories={categories.data ?? []}
        categoriesLoading={categories.isLoading}
        categoriesError={categories.isError}
      />

      <StockTable
        data={shownPage?.content ?? []}
        // La página mostrada se deriva de la data visible (no del estado UI): si un
        // refetch falla y retenemos `lastGoodPage`, el footer y los botones reflejan
        // la página realmente en pantalla, no la que se intentó cargar.
        page={shownPage?.page ?? page}
        size={PAGE_SIZE}
        total={shownPage?.totalElements ?? 0}
        totalPages={shownPage?.totalPages ?? 0}
        isLoading={isLoading}
        isFetching={isFetching}
        isError={isError}
        errorMessage={getApiErrorMessage(error, 'No se pudieron cargar las existencias.')}
        onRetry={() => refetch()}
        onPageChange={setPage}
        onRowClick={(product) => navigate(`/cotizaciones/almacen/productos/${product.id}`)}
        hasActiveFilters={hasActiveFilters}
      />
    </div>
  )
}
