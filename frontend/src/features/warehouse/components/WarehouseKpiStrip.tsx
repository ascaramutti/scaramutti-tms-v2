import { AlertTriangle, ArrowDownToLine, ArrowUpFromLine, Package, type LucideIcon } from 'lucide-react'
import type { WarehouseStatsResponse } from '../../../api'
import { Spinner } from '../../../shared/ui/Spinner'
import { cn } from '../../../shared/utils/cn'

interface WarehouseKpiStripProps {
  data?: WarehouseStatsResponse
  isLoading: boolean
  isError: boolean
  errorMessage?: string
  onRetry: () => void
  /** El corte "solo stock bajo" está aplicado en la tabla. */
  lowOnly: boolean
  onToggleLowOnly: () => void
}

interface TileProps {
  icon: LucideIcon
  label: string
  value: number | undefined
  isLoading: boolean
  highlight?: boolean
}

const TILE_CLASSES = 'rounded-xl border border-slate-200 bg-white p-4 text-left shadow-sm'

function TileBody({ icon: Icon, label, value, isLoading, highlight }: TileProps) {
  return (
    <>
      <span className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-slate-500">
        <Icon className="h-4 w-4" aria-hidden="true" />
        {label}
      </span>
      {isLoading ? (
        <Spinner size={18} label="Cargando indicadores" className="mt-2 text-blue-600" />
      ) : (
        <span
          className={cn(
            'mt-2 block text-2xl font-semibold tabular-nums',
            highlight ? 'text-amber-700' : 'text-slate-900',
          )}
        >
          {/* `0` es un dato, no un vacío: solo el undefined (stats en error) cae al guion. */}
          {value ?? '—'}
        </span>
      )}
    </>
  )
}

/**
 * Resumen del almacén: 4 indicadores del contrato de `GET /warehouse/stats`.
 *
 * Son totales de TODO el almacén y no reaccionan a los filtros de la tabla (el
 * endpoint no acepta parámetros), por eso los rótulos lo dicen. El único tile
 * interactivo es "Con stock bajo", que aplica ese corte a la tabla; los del mes
 * quedan informativos hasta que existan las pantallas de entradas y retiros.
 */
export function WarehouseKpiStrip({
  data,
  isLoading,
  isError,
  errorMessage,
  onRetry,
  lowOnly,
  onToggleLowOnly,
}: WarehouseKpiStripProps) {
  return (
    <div className="space-y-3">
      {/* Los KPIs son informativos: si fallan, se degrada el strip y la tabla sigue trabajando. */}
      {isError && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-800"
        >
          <span>{errorMessage ?? 'No se pudieron cargar los indicadores del almacén.'}</span>
          <button
            type="button"
            onClick={onRetry}
            // Nombre propio: si el listado también falla, su botón "Reintentar"
            // queda en la misma pantalla y serían dos nombres idénticos.
            aria-label="Reintentar cargar los indicadores"
            className="shrink-0 font-medium text-amber-900 underline underline-offset-2 hover:no-underline"
          >
            Reintentar
          </button>
        </div>
      )}

      <div role="group" aria-label="Resumen del almacén" className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className={TILE_CLASSES}>
          <TileBody
            icon={Package}
            label="Productos activos"
            value={data?.activeProducts}
            isLoading={isLoading}
          />
        </div>

        <button
          type="button"
          onClick={onToggleLowOnly}
          aria-pressed={lowOnly}
          // El aria-label reemplaza el contenido del tile como nombre accesible,
          // así que repite el dato: si solo dijera la acción, el único KPI
          // accionable sería además el único que un lector de pantalla no puede leer.
          aria-label={`Con stock bajo: ${data?.lowStockCount ?? 'sin dato'}. Filtrar: solo stock bajo`}
          className={cn(
            TILE_CLASSES,
            'transition focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2',
            lowOnly ? 'border-amber-400 bg-amber-50' : 'hover:bg-slate-50',
          )}
        >
          <TileBody
            icon={AlertTriangle}
            label="Con stock bajo"
            value={data?.lowStockCount}
            isLoading={isLoading}
            highlight={!!data && data.lowStockCount > 0}
          />
        </button>

        <div className={TILE_CLASSES}>
          <TileBody
            icon={ArrowDownToLine}
            label="Entradas del mes"
            value={data?.entriesThisMonth}
            isLoading={isLoading}
          />
        </div>

        <div className={TILE_CLASSES}>
          <TileBody
            icon={ArrowUpFromLine}
            label="Retiros del mes"
            value={data?.withdrawalsThisMonth}
            isLoading={isLoading}
          />
        </div>
      </div>
    </div>
  )
}
