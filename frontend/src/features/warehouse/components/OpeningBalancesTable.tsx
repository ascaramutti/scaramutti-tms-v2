import type { WarehouseOpeningBalanceResponse } from '../../../api'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatDateTime, formatQuantity } from '../../../shared/utils/formatters'

interface OpeningBalancesTableProps {
  data: WarehouseOpeningBalanceResponse[]
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
  /** Hay un producto filtrado: cambia el copy del estado vacío. */
  hasActiveFilter: boolean
  /**
   * El usuario puede registrar (es admin). Solo cambia el copy del estado vacío:
   * a quien no ve el formulario no se le puede pedir que lo use.
   */
  canRegister: boolean
}

/**
 * Listado de cortes iniciales registrados. Las filas NO son clickeables ni traen
 * acciones: la apertura es inmutable (el contrato no expone GET por id, PUT ni
 * DELETE), así que no hay detalle al que navegar ni nada que editar o anular. Es
 * un registro de auditoría de la carga de arranque.
 */
export function OpeningBalancesTable({
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
  hasActiveFilter,
  canRegister,
}: OpeningBalancesTableProps) {
  const columns: Column<WarehouseOpeningBalanceResponse>[] = [
    {
      key: 'product',
      header: 'Producto',
      render: (balance) => (
        <div>
          <p className="font-medium text-fg">{balance.product.name}</p>
          {balance.product.code && (
            <p className="text-xs text-fg-muted">{balance.product.code}</p>
          )}
        </div>
      ),
    },
    {
      key: 'quantity',
      header: 'Cantidad inicial',
      align: 'right',
      // El 0 es un valor legítimo (se contó y no había existencias) y se muestra
      // como tal: nada de guiones ni celdas vacías.
      render: (balance) => (
        <span className="tabular-nums text-fg">
          {formatQuantity(balance.quantity)} {balance.product.unitCode}
        </span>
      ),
    },
    {
      key: 'observations',
      header: 'Observaciones',
      render: (balance) =>
        balance.observations ? (
          <span className="text-fg-body">{balance.observations}</span>
        ) : (
          <span className="text-fg-subtle">Sin observaciones</span>
        ),
    },
    {
      key: 'registeredBy',
      header: 'Registró',
      render: (balance) => (
        <div>
          <p className="text-fg">{balance.registeredBy.fullName}</p>
          <p className="text-xs text-fg-muted">{formatDateTime(balance.registeredAt)}</p>
        </div>
      ),
    },
  ]

  return (
    <DataTable
      columns={columns}
      data={data}
      keyExtractor={(balance) => balance.id}
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
      caption="Aperturas por producto, cantidad inicial y quién la registró"
      emptyTitle={
        hasActiveFilter ? 'Ese producto no tiene corte inicial' : 'Aún no hay cortes iniciales'
      }
      // Sin permiso para registrar no se menciona el formulario: no está en la
      // pantalla, y pedir que se use algo que no se ve deja al usuario buscándolo.
      emptyDescription={
        canRegister
          ? hasActiveFilter
            ? 'Puedes registrarlo con el formulario de arriba.'
            : 'Registra el primero con el formulario de arriba.'
          : 'Los registra un administrador.'
      }
    />
  )
}
