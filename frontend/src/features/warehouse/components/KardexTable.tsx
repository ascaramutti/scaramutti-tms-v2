import type {
  WarehouseKardexMovementResponse,
  WarehouseKardexMovementType,
} from '../../../api'
import { Badge, type BadgeVariant } from '../../../shared/ui/Badge'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatDateTime, formatQuantity } from '../../../shared/utils/formatters'
import { cn } from '../../../shared/utils/cn'

interface KardexTableProps {
  data: WarehouseKardexMovementResponse[]
  page: number
  size: number
  total: number
  totalPages: number
  /** Unidad del producto: los movimientos traen cantidades, no la unidad. */
  unitCode: string
  isLoading: boolean
  isFetching: boolean
  isError: boolean
  errorMessage?: string
  onRetry: () => void
  onPageChange: (page: number) => void
}

const MOVEMENT_LABELS: Record<WarehouseKardexMovementType, string> = {
  APERTURA: 'Corte inicial',
  ENTRADA: 'Entrada',
  SALIDA: 'Salida',
}

const MOVEMENT_VARIANTS: Record<WarehouseKardexMovementType, BadgeVariant> = {
  APERTURA: 'slate',
  ENTRADA: 'success',
  // Rosa como la cantidad de la misma fila: el distintivo y el número tienen que
  // contar lo mismo (sale stock), no dos colores para el mismo hecho.
  SALIDA: 'danger',
}

/** Las salidas restan; apertura y entradas suman. El backend manda la cantidad siempre positiva. */
function isOutgoing(movementType: WarehouseKardexMovementType): boolean {
  return movementType === 'SALIDA'
}

/**
 * Clave de fila. El contrato no expone un id de movimiento (la vista une tres
 * orígenes distintos) y los campos que sí llegan NO alcanzan para identificarlo:
 * una factura con dos ítems del mismo producto genera dos entradas con el mismo
 * origen, el mismo tipo y la misma fecha (la de la factura). Se completa con la
 * posición en la página, que es estable porque el orden lo fija el backend y la
 * tabla no reordena ni filtra localmente.
 */
function movementKey(movement: WarehouseKardexMovementResponse, index: number): string {
  return `${movement.movementType}-${movement.sourceId ?? 'apertura'}-${movement.movedAt}-${index}`
}

/**
 * Kardex del producto: movimientos con su saldo corrido, del más reciente al más
 * antiguo (orden del backend, no se reordena acá).
 *
 * El saldo lo calcula el backend sobre la historia completa, así que sigue siendo
 * correcto en cualquier página: NO se acumula ni se recalcula en el front.
 */
export function KardexTable({
  data,
  page,
  size,
  total,
  totalPages,
  unitCode,
  isLoading,
  isFetching,
  isError,
  errorMessage,
  onRetry,
  onPageChange,
}: KardexTableProps) {
  // La clave se resuelve por posición una sola vez: `DataTable` solo pasa la fila
  // al `keyExtractor`, y buscar el índice ahí sería recorrer la página por fila.
  // `DataTable` itera este mismo array, así que toda fila está en el mapa.
  const keysByMovement = new Map(
    data.map((movement, index) => [movement, movementKey(movement, index)]),
  )

  const columns: Column<WarehouseKardexMovementResponse>[] = [
    {
      key: 'movedAt',
      header: 'Fecha',
      render: (movement) => (
        <span className="whitespace-nowrap text-slate-500">{formatDateTime(movement.movedAt)}</span>
      ),
    },
    {
      key: 'movementType',
      header: 'Movimiento',
      render: (movement) => (
        <Badge variant={MOVEMENT_VARIANTS[movement.movementType]}>
          {MOVEMENT_LABELS[movement.movementType]}
        </Badge>
      ),
    },
    {
      key: 'reference',
      header: 'Referencia',
      render: (movement) => movement.reference,
    },
    {
      key: 'registeredBy',
      header: 'Registró',
      render: (movement) => (
        <span className="text-slate-500">{movement.registeredBy.fullName}</span>
      ),
    },
    {
      key: 'quantity',
      header: 'Cantidad',
      align: 'right',
      render: (movement) => (
        <span
          className={cn(
            'font-medium tabular-nums',
            isOutgoing(movement.movementType) ? 'text-rose-700' : 'text-emerald-700',
          )}
        >
          {isOutgoing(movement.movementType) ? '−' : '+'}
          {formatQuantity(movement.quantity)} {unitCode}
        </span>
      ),
    },
    {
      key: 'balance',
      header: 'Saldo',
      align: 'right',
      render: (movement) => (
        <span className="font-semibold tabular-nums text-slate-900">
          {formatQuantity(movement.balance)} {unitCode}
        </span>
      ),
    },
  ]

  return (
    <DataTable
      columns={columns}
      data={data}
      keyExtractor={(movement) => keysByMovement.get(movement) as string}
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
      caption="Kardex del producto"
      emptyTitle="Todavía no hay movimientos"
      emptyDescription="El kardex se puebla con el corte inicial, las entradas de compra y los retiros."
    />
  )
}
