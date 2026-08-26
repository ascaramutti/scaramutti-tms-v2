import type { WarehouseWithdrawalResponse } from '../../../api'
import { Badge } from '../../../shared/ui/Badge'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatDateOnly, formatQuantity } from '../../../shared/utils/formatters'
import { fleetUnitLabel } from '../../../shared/catalogs/fleetUnit'

interface WithdrawalsTableProps {
  data: WarehouseWithdrawalResponse[]
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
  onRowClick: (withdrawal: WarehouseWithdrawalResponse) => void
}

/**
 * Listado de retiros (salidas de stock). La fila entera abre el detalle del retiro
 * (`onRowClick`), anulados incluidos: ahí se ve el motivo de la anulación. La
 * cantidad y el producto se muestran tal como los devuelve el backend; el frontend
 * no recalcula nada.
 */
export function WithdrawalsTable({
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
}: WithdrawalsTableProps) {
  const columns: Column<WarehouseWithdrawalResponse>[] = [
    {
      key: 'product',
      header: 'Producto',
      render: (withdrawal) => (
        <div>
          <p className="font-medium text-slate-900">{withdrawal.product.name}</p>
          {withdrawal.product.code && (
            <p className="text-xs text-slate-500">{withdrawal.product.code}</p>
          )}
        </div>
      ),
    },
    {
      key: 'quantity',
      header: 'Cantidad',
      align: 'right',
      render: (withdrawal) => (
        <span className="tabular-nums text-slate-900">
          {formatQuantity(withdrawal.quantity)} {withdrawal.product.unitCode}
        </span>
      ),
    },
    {
      key: 'receivedBy',
      header: 'Recibido por',
      // Quién registró va como sublínea de auditoría, no en su propia columna.
      render: (withdrawal) => (
        <div>
          <p className="text-slate-900">{withdrawal.receivedBy.fullName}</p>
          <p className="text-xs text-slate-500">Registró {withdrawal.registeredBy.fullName}</p>
        </div>
      ),
    },
    {
      key: 'fleetUnit',
      header: 'Unidad',
      render: (withdrawal) =>
        withdrawal.fleetUnit ? (
          <span className="text-slate-700">{fleetUnitLabel(withdrawal.fleetUnit)}</span>
        ) : (
          <span className="text-slate-400">Sin unidad</span>
        ),
    },
    {
      key: 'withdrawnAt',
      header: 'Fecha',
      render: (withdrawal) => formatDateOnly(withdrawal.withdrawnAt),
    },
    {
      key: 'status',
      header: 'Estado',
      // El estado nunca se comunica solo por color: el badge siempre lleva texto.
      render: (withdrawal) =>
        withdrawal.status === 'CANCELLED' ? (
          <div>
            <Badge variant="danger">Anulado</Badge>
            {withdrawal.cancelReason && (
              <p className="mt-1 text-xs text-slate-500">{withdrawal.cancelReason}</p>
            )}
          </div>
        ) : (
          <Badge variant="success">Activo</Badge>
        ),
    },
  ]

  return (
    <DataTable
      columns={columns}
      data={data}
      keyExtractor={(withdrawal) => withdrawal.id}
      onRowClick={onRowClick}
      rowLabel={(withdrawal) => `Ver el retiro de ${withdrawal.product.name}`}
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
      caption="Retiros del almacén (salidas de stock)"
      emptyTitle={hasActiveFilters ? 'No se encontraron retiros' : 'Aún no hay retiros'}
      emptyDescription={
        hasActiveFilters
          ? 'Prueba ajustar los filtros de búsqueda.'
          : 'Registra el primer retiro para descontar stock del almacén.'
      }
    />
  )
}
