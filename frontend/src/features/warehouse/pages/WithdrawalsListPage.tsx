import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { cn } from '../../../shared/utils/cn'
import { useLastGoodPage } from '../../../shared/hooks/useLastGoodPage'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { WithdrawalsFilterBar } from '../components/WithdrawalsFilterBar'
import { WithdrawalsTable } from '../components/WithdrawalsTable'
import { useWarehouseWithdrawalsList } from '../hooks/useWarehouseWithdrawalsList'
import {
  EMPTY_WITHDRAWAL_FILTERS,
  type WithdrawalFilters,
} from '../schemas/withdrawal-filters.schema'
import { buttonClasses } from '../../../shared/ui/buttonClasses'

const PAGE_SIZE = 10

/** Un rango invertido no se consulta: devolvería vacío sin poder distinguirlo de "no hay". */
function hasValidRange(filters: WithdrawalFilters): boolean {
  return !filters.dateFrom || !filters.dateTo || filters.dateFrom <= filters.dateTo
}

/** Retiros del almacén: las salidas que descuentan stock. */
export function WithdrawalsListPage() {
  const navigate = useNavigate()
  const [filters, setFilters] = useState<WithdrawalFilters>(EMPTY_WITHDRAWAL_FILTERS)
  const [page, setPage] = useState(0)

  const effectiveFilters = useMemo<WithdrawalFilters>(() => {
    if (hasValidRange(filters)) return filters
    return { ...filters, dateFrom: undefined, dateTo: undefined }
  }, [filters])

  const { data, isLoading, isFetching, isError, error, refetch } = useWarehouseWithdrawalsList({
    page,
    size: PAGE_SIZE,
    filters: effectiveFilters,
  })

  const shownPage = useLastGoodPage(data)

  function handleFiltersChange(next: WithdrawalFilters) {
    setFilters(next)
    setPage(0) // cualquier cambio de filtro reinicia a la primera página
  }

  const hasActiveFilters =
    !!effectiveFilters.status ||
    !!effectiveFilters.productId ||
    !!effectiveFilters.receivedByWorkerId ||
    !!effectiveFilters.tractorId ||
    !!effectiveFilters.trailerId ||
    !!effectiveFilters.escortVehicleId ||
    !!effectiveFilters.dateFrom ||
    !!effectiveFilters.dateTo

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Retiros"
        description="Salidas de stock · cada retiro descuenta existencias."
        divider
        action={
          <Link to="/cotizaciones/almacen/retiros/nuevo" className={cn(buttonClasses({ variant: 'primary' }), 'gap-1.5')}>
            <Plus className="h-4 w-4" aria-hidden="true" />
            Registrar retiro
          </Link>
        }
      />

      <WithdrawalsFilterBar value={filters} onChange={handleFiltersChange} />

      <WithdrawalsTable
        data={shownPage?.content ?? []}
        page={shownPage?.page ?? page}
        size={PAGE_SIZE}
        total={shownPage?.totalElements ?? 0}
        totalPages={shownPage?.totalPages ?? 0}
        isLoading={isLoading}
        isFetching={isFetching}
        isError={isError}
        errorMessage={getApiErrorMessage(error, 'No se pudieron cargar los retiros.')}
        onRetry={() => refetch()}
        onPageChange={setPage}
        hasActiveFilters={hasActiveFilters}
        onRowClick={(withdrawal) =>
          navigate(`/cotizaciones/almacen/retiros/${withdrawal.id}`)
        }
      />
    </div>
  )
}
