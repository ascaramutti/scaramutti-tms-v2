import { Link } from 'react-router-dom'
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
  APERTURA: 'default',
  ENTRADA: 'success',
  // Del mismo color que la cantidad de la misma fila: el distintivo y el número tienen
  // que contar lo mismo (sale stock), no dos colores para el mismo hecho. Los dos pasaron
  // juntos del rosa al rojo de peligro cuando se unificó la familia, y por eso se cambian
  // juntos: si uno se mueve sin el otro, la fila cuenta dos cosas.
  SALIDA: 'danger',
}

/**
 * Pantalla de detalle del documento que originó el movimiento. La APERTURA queda
 * afuera a propósito: el corte inicial no tiene documento que abrir.
 */
const MOVEMENT_SOURCE_PATHS: Record<Exclude<WarehouseKardexMovementType, 'APERTURA'>, string> = {
  ENTRADA: '/cotizaciones/almacen/entradas',
  SALIDA: '/cotizaciones/almacen/retiros',
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
        <span className="whitespace-nowrap text-fg-muted">{formatDateTime(movement.movedAt)}</span>
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
      // ENTRADA y SALIDA linkean a su origen (`sourceId` es el id de la factura o
      // del retiro, según el tipo). La APERTURA no tiene origen que abrir
      // (`sourceId` null) y queda como texto plano.
      render: (movement) =>
        movement.movementType !== 'APERTURA' && movement.sourceId != null ? (
          <Link
            to={`${MOVEMENT_SOURCE_PATHS[movement.movementType]}/${movement.sourceId}`}
            className="rounded font-medium text-accent hover:text-accent-hover hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            {movement.reference}
          </Link>
        ) : (
          movement.reference
        ),
    },
    {
      key: 'registeredBy',
      header: 'Registró',
      render: (movement) => (
        <span className="text-fg-muted">{movement.registeredBy.fullName}</span>
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
            isOutgoing(movement.movementType) ? 'text-danger-fg' : 'text-success-fg',
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
        <span className="font-semibold tabular-nums text-fg">
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
