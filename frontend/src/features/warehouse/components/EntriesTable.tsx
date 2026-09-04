import type { WarehousePurchaseInvoiceSummary } from '../../../api'
import { Badge } from '../../../shared/ui/Badge'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatCurrency, formatDateOnly } from '../../../shared/utils/formatters'

interface EntriesTableProps {
  data: WarehousePurchaseInvoiceSummary[]
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
  /** Hay filtros aplicados: cambia el copy del estado vacío. */
  hasActiveFilters: boolean
  /** Navega al detalle de la entrada al hacer clic en una fila. */
  onRowClick: (invoice: WarehousePurchaseInvoiceSummary) => void
}

/**
 * Listado de entradas (facturas de compra). Cada fila navega al detalle de la
 * entrada (`onRowClick`).
 *
 * `itemsCount` y `total` se muestran tal como los devuelve el backend, que los
 * deriva de los ítems: el frontend no los recalcula.
 */
export function EntriesTable({
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
  hasActiveFilters,
  onRowClick,
}: EntriesTableProps) {
  const columns: Column<WarehousePurchaseInvoiceSummary>[] = [
    {
      key: 'invoiceNumber',
      header: 'N° factura',
      render: (invoice) => (
        <div>
          <p className="font-medium tabular-nums text-fg">{invoice.invoiceNumber}</p>
          {invoice.guideNumber && (
            <p className="text-xs text-fg-muted">Guía {invoice.guideNumber}</p>
          )}
        </div>
      ),
    },
    {
      key: 'supplier',
      header: 'Proveedor',
      // Quién registró va como sublínea y no en su propia columna: es dato de
      // auditoría útil, pero una séptima columna aprieta la tabla sin necesidad.
      render: (invoice) => (
        <div>
          <p className="text-fg">{invoice.supplier.name}</p>
          <p className="text-xs text-fg-muted">Registró {invoice.registeredBy.fullName}</p>
        </div>
      ),
    },
    {
      key: 'invoiceDate',
      header: 'Fecha',
      render: (invoice) => formatDateOnly(invoice.invoiceDate),
    },
    {
      key: 'itemsCount',
      header: 'Ítems',
      align: 'right',
      render: (invoice) => (
        <span className="tabular-nums text-fg-body">{invoice.itemsCount}</span>
      ),
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (invoice) => (
        <span className="tabular-nums text-fg">
          {formatCurrency(invoice.total, invoice.currencyCode)}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Estado',
      // El estado nunca se comunica solo por color: el badge siempre lleva texto.
      render: (invoice) =>
        invoice.status === 'CANCELLED' ? (
          <div>
            <Badge variant="danger">Anulada</Badge>
            {invoice.cancelReason && (
              <p className="mt-1 text-xs text-fg-muted">{invoice.cancelReason}</p>
            )}
          </div>
        ) : (
          <Badge variant="success">Activa</Badge>
        ),
    },
  ]

  return (
    <DataTable
      columns={columns}
      data={data}
      keyExtractor={(invoice) => invoice.id}
      onRowClick={onRowClick}
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
      caption="Entradas del almacén (facturas de compra)"
      emptyTitle={hasActiveFilters ? 'No se encontraron entradas' : 'Aún no hay entradas'}
      emptyDescription={
        hasActiveFilters
          ? 'Prueba ajustar los filtros de búsqueda.'
          : 'Registra la primera factura de compra para que el stock empiece a moverse.'
      }
    />
  )
}
