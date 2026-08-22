import type { ServiceSummaryResponse } from '../../../api'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatCurrency, formatDateOnly } from '../../../shared/utils/formatters'
import { TRIP_SCOPE_LABELS } from '../status/serviceStatusPresentation'
import { ServiceStatusBadge } from './ServiceStatusBadge'

interface ServicesTableProps {
  data: ServiceSummaryResponse[]
  page: number
  size: number
  total: number
  totalPages: number
  onPageChange: (page: number) => void
  isLoading: boolean
  isFetching: boolean
  isError: boolean
  errorMessage?: string
  onRetry: () => void
  /** `false` para el despacho: la columna de precio no se arma. */
  showPrice: boolean
  /** Cambia el texto del estado vacío: sin resultados no es lo mismo que sin datos. */
  hasActiveFilters: boolean
}

/**
 * Precio de la fila. Se degrada a guion si el importe o su moneda no vienen:
 * el contrato los marca opcionales (el servidor se los omite al despacho), así
 * que una fila sin ellos tiene que renderizarse igual en vez de romper.
 */
function renderPrice(row: ServiceSummaryResponse) {
  if (row.price == null || !row.currencyCode) return '—'
  return (
    <span className="font-medium tabular-nums text-slate-900">
      {formatCurrency(row.price, row.currencyCode)}
    </span>
  )
}

/**
 * Tabla del listado de servicios.
 *
 * El orden es fijo por fecha de registro descendente (el contrato no expone
 * parámetro de ordenamiento), y el `caption` lo dice para que nadie lea la tabla
 * como ordenada por la fecha tentativa que sí se muestra.
 *
 * Las filas no son clickeables todavía: la pantalla de detalle no existe, y una
 * fila que navega a una ruta inexistente es peor que una fila inerte.
 */
export function ServicesTable({
  data,
  page,
  size,
  total,
  totalPages,
  onPageChange,
  isLoading,
  isFetching,
  isError,
  errorMessage,
  onRetry,
  showPrice,
  hasActiveFilters,
}: ServicesTableProps) {
  const columns: Column<ServiceSummaryResponse>[] = [
    {
      key: 'code',
      header: 'Código',
      render: (row) => <span className="font-semibold text-blue-700">{row.code}</span>,
    },
    {
      key: 'client',
      header: 'Cliente',
      render: (row) => (
        <div>
          <span className="block font-medium text-slate-900">{row.client.name}</span>
          <span className="block text-xs text-slate-500">{row.client.ruc}</span>
        </div>
      ),
    },
    {
      key: 'route',
      header: 'Ruta',
      render: (row) => (
        <div>
          <span className="block text-slate-900">
            {row.origin} → {row.destination}
          </span>
          {/* El ámbito va en mayúsculas por CSS, no como literal: un lector de
              pantalla deletrea las mayúsculas escritas a mano ("P-R-O-V…"). */}
          <span className="block text-xs font-bold uppercase tracking-wide text-slate-600">
            {TRIP_SCOPE_LABELS[row.tripScope]}
          </span>
        </div>
      ),
    },
    {
      key: 'resources',
      header: 'Conductor / Unidad',
      render: (row) =>
        row.driver || row.tractor ? (
          <div>
            <span className="block text-slate-900">{row.driver?.fullName ?? '—'}</span>
            <span className="block text-xs text-slate-500">{row.tractor?.plate ?? '—'}</span>
          </div>
        ) : (
          <span className="text-slate-500">Sin asignar</span>
        ),
    },
  ]

  // El importe va antes del estado y la fecha cierra la fila, como en el listado
  // de cotizaciones: las dos tablas se leen igual.
  if (showPrice) {
    columns.push({
      key: 'price',
      header: 'Precio',
      align: 'right',
      render: renderPrice,
    })
  }

  columns.push(
    {
      key: 'status',
      header: 'Estado',
      render: (row) => <ServiceStatusBadge status={row.status} />,
    },
    {
      key: 'tentativeDate',
      header: 'Fecha tentativa',
      // Date-only: va por `formatDateOnly`. Pasarla por `formatDate` la
      // interpretaría como medianoche UTC y en Lima mostraría el día anterior.
      render: (row) => (
        <span className="text-slate-500">{formatDateOnly(row.tentativeDate)}</span>
      ),
    },
  )

  return (
    <div className="space-y-2">
      <DataTable
        columns={columns}
        data={data}
        keyExtractor={(row) => row.id}
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
        emptyTitle={hasActiveFilters ? 'No se encontraron servicios' : 'Aún no hay servicios'}
        emptyDescription={
        hasActiveFilters
          ? 'Prueba ajustar los filtros de búsqueda.'
          : 'Todavía no hay servicios registrados.'
        }
        caption="Servicios, ordenados por fecha de registro (más recientes primero)"
      />
      {/* El orden no coincide con ninguna columna visible: la fecha que se muestra
          es la tentativa. Decirlo solo en el `caption`, que es sr-only, lo deja
          fuera del alcance de quien mira la pantalla. */}
      <p className="text-xs text-slate-500">
        Ordenados por fecha de registro, los más recientes primero.
      </p>
    </div>
  )
}
