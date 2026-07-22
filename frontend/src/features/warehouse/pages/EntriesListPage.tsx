import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { PRIMARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { cn } from '../../../shared/utils/cn'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { useLastGoodPage } from '../../../shared/hooks/useLastGoodPage'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { EntriesFilterBar } from '../components/EntriesFilterBar'
import { EntriesTable } from '../components/EntriesTable'
import {
  ENTRY_SEARCH_MIN_LENGTH,
  useWarehousePurchaseInvoicesList,
} from '../hooks/useWarehousePurchaseInvoicesList'
import { EMPTY_ENTRY_FILTERS, type EntryFilters } from '../schemas/entry-filters.schema'

const PAGE_SIZE = 10
const SEARCH_DEBOUNCE_MS = 300

/** Un rango invertido no se consulta: devolvería vacío sin poder distinguirlo de "no hay". */
function hasValidRange(filters: EntryFilters): boolean {
  return !filters.dateFrom || !filters.dateTo || filters.dateFrom <= filters.dateTo
}

/** Entradas del almacén: las facturas de compra que suman stock. */
export function EntriesListPage() {
  const [filters, setFilters] = useState<EntryFilters>(EMPTY_ENTRY_FILTERS)
  const [page, setPage] = useState(0)

  // Solo la búsqueda libre se debouncea; los selects y las fechas aplican al instante.
  const debouncedQ = useDebouncedValue(filters.q, SEARCH_DEBOUNCE_MS)
  const effectiveFilters = useMemo<EntryFilters>(() => {
    const withDebouncedQ = { ...filters, q: debouncedQ }
    if (hasValidRange(withDebouncedQ)) return withDebouncedQ
    return { ...withDebouncedQ, dateFrom: undefined, dateTo: undefined }
  }, [filters, debouncedQ])

  const { data, isLoading, isFetching, isError, error, refetch } =
    useWarehousePurchaseInvoicesList({ page, size: PAGE_SIZE, filters: effectiveFilters })

  const shownPage = useLastGoodPage(data)

  function handleFiltersChange(next: EntryFilters) {
    setFilters(next)
    setPage(0) // cualquier cambio de filtro reinicia a la primera página
  }

  const hasActiveFilters =
    effectiveFilters.q.trim().length >= ENTRY_SEARCH_MIN_LENGTH ||
    !!effectiveFilters.status ||
    !!effectiveFilters.dateFrom ||
    !!effectiveFilters.dateTo

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Entradas"
        description="Compras por factura · cada ítem registrado suma stock."
        divider
        action={
          <Link to="/cotizaciones/almacen/entradas/nueva" className={cn(PRIMARY_BUTTON, 'gap-1.5')}>
            <Plus className="h-4 w-4" aria-hidden="true" />
            Registrar entrada
          </Link>
        }
      />

      <EntriesFilterBar value={filters} onChange={handleFiltersChange} />

      <EntriesTable
        data={shownPage?.content ?? []}
        // La página mostrada se deriva de la data visible (no del estado UI): si un
        // refetch falla y retenemos la última página buena, el footer y los botones
        // reflejan la página realmente en pantalla.
        page={shownPage?.page ?? page}
        size={PAGE_SIZE}
        total={shownPage?.totalElements ?? 0}
        totalPages={shownPage?.totalPages ?? 0}
        isLoading={isLoading}
        isFetching={isFetching}
        isError={isError}
        errorMessage={getApiErrorMessage(error, 'No se pudieron cargar las entradas.')}
        onRetry={() => refetch()}
        onPageChange={setPage}
        hasActiveFilters={hasActiveFilters}
      />
    </div>
  )
}
