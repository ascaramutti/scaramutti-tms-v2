import { useState } from 'react'
import { Download, FileBarChart2 } from 'lucide-react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { Spinner } from '../../../shared/ui/Spinner'
import { csvBlob } from '../../../shared/utils/csv'
import { downloadBlob } from '../../../shared/utils/downloadBlob'
import { formatDateOnly } from '../../../shared/utils/formatters'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { ReportFilterBar } from '../components/ReportFilterBar'
import { ReportRowsList } from '../components/ReportRowsList'
import { ReportTotalsCards } from '../components/ReportTotalsCards'
import { useWarehouseReport } from '../hooks/useWarehouseReport'
import {
  defaultReportFilters,
  isReportRangeValid,
  type ReportFilters,
} from '../schemas/report-filters.schema'
import { buildReportCsv, reportCsvFilename } from '../utils/reportCsv'
import { reportCutMeta } from '../utils/reportCuts'
import { Button } from '../../../shared/ui/Button'

/**
 * Reportes del almacén: los 4 cortes agregados de `GET /warehouse/reports`.
 *
 * RN-WH7, bi-moneda SIN conversión: los montos se muestran por moneda tal como
 * se registró cada factura, y no existe un total unificado ni un tipo de cambio.
 * Almacén entrega cantidades; contabilidad valoriza sobre este reporte.
 *
 * Todo lo que se rotula (corte, rango, columnas) sale de la RESPUESTA y no del
 * estado local: mientras un cambio de corte está en vuelo, la tabla previa sigue
 * en pantalla y debe seguir describiéndose a sí misma, no al corte pedido.
 */
export function WarehouseReportsPage() {
  const [filters, setFilters] = useState<ReportFilters>(defaultReportFilters)
  const { data, isLoading, isFetching, isError, error, refetch } = useWarehouseReport(filters)

  const hasRows = !!data && data.rows.length > 0
  // Con un rango inválido la consulta no corre y en pantalla queda el reporte
  // anterior: exportarlo daría un archivo del rango viejo con los inputs
  // mostrando otro. Mientras el filtro no aplique, no se exporta.
  const canExport = hasRows && isReportRangeValid(filters)

  function handleExport() {
    if (!data) return
    downloadBlob(csvBlob(buildReportCsv(data)), reportCsvFilename(data))
  }

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Reportes de almacén"
        description="Consumo y compras agregados por período — la base del costo de mantenimiento por unidad."
        divider
        action={
          <Button
            variant="primary"
            onClick={handleExport}
            disabled={!canExport}
            className={!canExport ? 'cursor-not-allowed opacity-50' : undefined}
          >
            <Download className="mr-2 h-4 w-4" aria-hidden="true" />
            Exportar CSV
          </Button>
        }
      />

      <ReportFilterBar value={filters} onChange={setFilters} />

      {isError && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
        >
          <span>{getApiErrorMessage(error, 'No se pudo generar el reporte.')}</span>
          <button
            type="button"
            onClick={() => refetch()}
            className="shrink-0 font-medium text-red-900 underline underline-offset-2 hover:no-underline"
          >
            Reintentar
          </button>
        </div>
      )}

      {/* El cambio de corte o de rango no mueve el foco: hay que anunciarlo. Se
          anuncia un resumen y no el panel entero, que reanunciaría cada fila en
          cada refetch. */}
      <p className="sr-only" aria-live="polite">
        {data
          ? `${reportCutMeta(data.cut).label}: ${data.rows.length} filas, del ${formatDateOnly(data.dateFrom)} al ${formatDateOnly(data.dateTo)}`
          : ''}
      </p>

      <div
        id="report-panel"
        role="tabpanel"
        // El tab SELECCIONADO etiqueta el panel, aunque con `keepPreviousData`
        // el contenido sea todavía del corte anterior: es la relación que pide
        // WAI-ARIA, y el `aria-busy` avisa que lo que se ve está por cambiar.
        aria-labelledby={`report-tab-${filters.cut}`}
        aria-busy={isFetching}
        // El panel no tiene contenido enfocable propio: sin esto, el teclado no
        // puede alcanzar los resultados desde el tablist.
        tabIndex={0}
        className="space-y-6"
      >
        {isLoading ? (
          <div className="flex justify-center py-16">
            <Spinner size={28} label="Generando reporte" className="text-blue-600" />
          </div>
        ) : data ? (
          <>
            <p className="text-sm text-slate-500">
              Del {formatDateOnly(data.dateFrom)} al {formatDateOnly(data.dateTo)}
            </p>
            <ReportTotalsCards report={data} />
            {hasRows ? (
              <ReportRowsList report={data} />
            ) : (
              <EmptyState
                icon={FileBarChart2}
                title="Sin movimientos en el rango"
                description="No hay datos para el corte y el período seleccionados."
              />
            )}
          </>
        ) : null}
      </div>

      <p className="text-xs text-slate-400">
        El valor de las salidas usa el último precio de compra de cada producto como referencia, y se
        muestra <strong>por moneda tal como se registró</strong>: almacén no convierte ni valoriza.
        La barra es referencia visual del ranking.
      </p>
    </div>
  )
}
