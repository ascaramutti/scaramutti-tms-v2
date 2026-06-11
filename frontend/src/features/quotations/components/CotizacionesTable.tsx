import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatCurrency, formatDate } from '../../../shared/utils/formatters'
import { QUOTATION_TYPE_LABELS } from '../utils/quotationLabels'
import { QuotationStatusBadge } from './QuotationStatusBadge'
import type { QuotationSummary } from '../../../api'

interface CotizacionesTableProps {
  data: QuotationSummary[]
  page: number
  size: number
  total: number
  totalPages: number
  isLoading: boolean
  isFetching: boolean
  isError: boolean
  errorMessage?: string
  onRetry: () => void
  onPageChange: (page: number) => void
  onRowClick: (quotation: QuotationSummary) => void
  /** Hay filtros aplicados — cambia el copy del estado vacío. */
  hasActiveFilters: boolean
}

/** Ruta origen → destino. Para ALQUILER (sin ruta) o datos incompletos: "—". */
function renderRoute(quotation: QuotationSummary): string {
  const { origin, destination } = quotation
  if (origin && destination) return `${origin} → ${destination}`
  return '—'
}

export function CotizacionesTable({
  data,
  page,
  size,
  total,
  totalPages,
  isLoading,
  isFetching,
  isError,
  errorMessage,
  onRetry,
  onPageChange,
  onRowClick,
  hasActiveFilters,
}: CotizacionesTableProps) {
  const columns: Column<QuotationSummary>[] = [
    {
      key: 'code',
      header: 'Código',
      render: (quotation) => (
        <span className="font-semibold text-blue-700">{quotation.code}</span>
      ),
    },
    {
      key: 'quotationType',
      header: 'Tipo',
      render: (quotation) => QUOTATION_TYPE_LABELS[quotation.quotationType],
    },
    {
      key: 'client',
      header: 'Cliente',
      render: (quotation) => (
        <div>
          <p className="font-medium text-slate-900">{quotation.client.name}</p>
          <p className="text-xs text-slate-500">{quotation.client.ruc}</p>
        </div>
      ),
    },
    {
      key: 'route',
      header: 'Ruta',
      render: renderRoute,
    },
    {
      key: 'itemsCount',
      header: 'Ítems',
      align: 'center',
      render: (quotation) => quotation.itemsCount,
    },
    {
      key: 'totalAmount',
      header: 'Total',
      align: 'right',
      render: (quotation) => (
        <span className="font-medium tabular-nums text-slate-900">
          {formatCurrency(quotation.totalAmount, quotation.currencyCode)}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Estado',
      render: (quotation) => (
        <QuotationStatusBadge status={quotation.status} isExpired={quotation.isExpired} />
      ),
    },
    {
      key: 'createdAt',
      header: 'Fecha',
      render: (quotation) => (
        <span className="text-slate-500">{formatDate(quotation.createdAt)}</span>
      ),
    },
  ]

  return (
    <DataTable
      columns={columns}
      data={data}
      keyExtractor={(quotation) => quotation.id}
      page={page}
      size={size}
      total={total}
      totalPages={totalPages}
      onPageChange={onPageChange}
      isLoading={isLoading}
      isFetching={isFetching}
      isError={isError}
      errorMessage={errorMessage}
      onRetry={onRetry}
      onRowClick={onRowClick}
      rowLabel={(quotation) => `Ver cotización ${quotation.code} de ${quotation.client.name}`}
      caption="Listado de cotizaciones"
      emptyTitle={hasActiveFilters ? 'No se encontraron cotizaciones' : 'Aún no hay cotizaciones'}
      emptyDescription={
        hasActiveFilters
          ? 'Prueba ajustar los filtros de búsqueda.'
          : 'Crea la primera con el botón "Nueva cotización".'
      }
    />
  )
}
