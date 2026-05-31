import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus } from 'lucide-react'
import type { PageOfQuotationSummary } from '../../../api'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { CotizacionesFilterBar } from '../components/CotizacionesFilterBar'
import { CotizacionesTable } from '../components/CotizacionesTable'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { SEARCH_MIN_LENGTH, useQuotationsList } from '../hooks/useQuotationsList'
import {
  EMPTY_QUOTATION_FILTERS,
  type QuotationFilters,
} from '../schemas/quotation-filters.schema'

const PAGE_SIZE = 10
const SEARCH_DEBOUNCE_MS = 300

export function CotizacionesListPage() {
  const navigate = useNavigate()
  const [filters, setFilters] = useState<QuotationFilters>(EMPTY_QUOTATION_FILTERS)
  const [page, setPage] = useState(0)

  // Solo la búsqueda libre se debouncea; los selects/fechas aplican al instante.
  const debouncedQ = useDebouncedValue(filters.q, SEARCH_DEBOUNCE_MS)
  const effectiveFilters = useMemo<QuotationFilters>(
    () => ({ ...filters, q: debouncedQ }),
    [filters, debouncedQ],
  )

  const { data, isLoading, isFetching, isError, error, refetch } = useQuotationsList({
    page,
    size: PAGE_SIZE,
    filters: effectiveFilters,
  })

  // react-query descarta `data` al entrar en error (placeholderData solo aplica
  // en estado pending). Conservamos la última página exitosa para no vaciar la
  // tabla si un refetch (al paginar/filtrar) falla: se muestra la data previa
  // con un aviso no destructivo en lugar del error a pantalla completa.
  // Ajuste de estado durante el render (patrón recomendado por React en vez de
  // un useEffect): es condicional y converge (en el re-render `data === lastGoodPage`).
  const [lastGoodPage, setLastGoodPage] = useState<PageOfQuotationSummary | undefined>(undefined)
  if (data && data !== lastGoodPage) {
    setLastGoodPage(data)
  }
  const shownPage = data ?? lastGoodPage

  function handleFiltersChange(next: QuotationFilters) {
    setFilters(next)
    setPage(0) // cualquier cambio de filtro reinicia a la primera página
  }

  const hasActiveFilters =
    effectiveFilters.q.trim().length >= SEARCH_MIN_LENGTH ||
    !!effectiveFilters.status ||
    !!effectiveFilters.quotationType ||
    !!effectiveFilters.dateFrom ||
    !!effectiveFilters.dateTo

  return (
    <div className="mx-auto max-w-7xl space-y-6 px-6 py-8">
      <PageHeader
        title="Cotizaciones"
        description="Gestión de cotizaciones comerciales · Transportes Scaramutti S.A.C."
        divider
        action={
          <button
            type="button"
            onClick={() => navigate('/cotizaciones/nueva')}
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
          >
            <Plus className="h-4 w-4" aria-hidden="true" />
            Nueva cotización
          </button>
        }
      />

      <CotizacionesFilterBar value={filters} onChange={handleFiltersChange} />

      <CotizacionesTable
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
        errorMessage={getApiErrorMessage(error, 'No se pudieron cargar las cotizaciones.')}
        onRetry={() => refetch()}
        onPageChange={setPage}
        onRowClick={(quotation) => navigate(`/cotizaciones/${quotation.id}`)}
        hasActiveFilters={hasActiveFilters}
      />
    </div>
  )
}
