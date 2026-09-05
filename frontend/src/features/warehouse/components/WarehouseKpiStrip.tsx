import { AlertTriangle, ArrowDownToLine, ArrowUpFromLine, Package } from 'lucide-react'
import type { WarehouseStatsResponse } from '../../../api'
import { cn } from '../../../shared/utils/cn'
import { KpiTile } from '../../../shared/ui/KpiTile'
import { Alert } from '../../../shared/ui/Alert'

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
        <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-warning-fg">
          <span>{errorMessage ?? 'No se pudieron cargar los indicadores del almacén.'}</span>
          <button
            type="button"
            onClick={onRetry}
            // Nombre propio: si el listado también falla, su botón "Reintentar"
            // queda en la misma pantalla y serían dos nombres idénticos.
            aria-label="Reintentar cargar los indicadores"
            className="shrink-0 font-medium text-warning-fg underline underline-offset-2 hover:no-underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            Reintentar
          </button>
        </Alert>
      )}

      <div role="group" aria-label="Resumen del almacén" className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          icon={Package}
          label="Productos activos"
          value={data?.activeProducts}
          isLoading={isLoading}
        />

        <KpiTile
          as="button"
          type="button"
          onClick={onToggleLowOnly}
          aria-pressed={lowOnly}
          // El aria-label reemplaza el contenido del tile como nombre accesible,
          // así que repite el dato: si solo dijera la acción, el único KPI
          // accionable sería además el único que un lector de pantalla no puede leer.
          aria-label={`Con stock bajo: ${data?.lowStockCount ?? 'sin dato'}. Filtrar: solo stock bajo`}
          className={cn(
            'transition focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-canvas',
            lowOnly ? 'border-warning bg-warning-soft' : 'hover:bg-surface-subtle',
          )}
          icon={AlertTriangle}
          label="Con stock bajo"
          value={data?.lowStockCount}
          isLoading={isLoading}
          highlight={!!data && data.lowStockCount > 0}
        />

        <KpiTile
          icon={ArrowDownToLine}
          label="Entradas del mes"
          value={data?.entriesThisMonth}
          isLoading={isLoading}
        />

        <KpiTile
          icon={ArrowUpFromLine}
          label="Retiros del mes"
          value={data?.withdrawalsThisMonth}
          isLoading={isLoading}
        />
      </div>
    </div>
  )
}
