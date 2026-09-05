import type { WarehouseReportResponse, WarehouseReportRowResponse } from '../../../api'
import { formatCurrency, formatDateOnly, formatQuantity } from '../../../shared/utils/formatters'
import { reportCutMeta } from '../utils/reportCuts'
import { Card } from '../../../shared/ui/Card'

interface ReportRowsListProps {
  report: WarehouseReportResponse
}

/**
 * Peso de la fila para la barra de ranking. Suma nominal de ambas monedas: es
 * exactamente el criterio con el que el backend ordena las filas, así que la
 * barra ilustra ESE ranking. No es una conversión (los montos siempre se
 * muestran por moneda separada) y por eso la barra no lleva número propio.
 */
function rowWeight(row: WarehouseReportRowResponse): number {
  return row.amountPEN + row.amountUSD
}

/**
 * Montos de la fila, por moneda. Se omite la moneda sin monto, pero el corte es
 * contra cero y no contra "positivo": un ajuste o una nota de crédito futura
 * daría negativo, y esa fila tiene valor que mostrar. Sin ningún monto, se
 * muestra el cero en soles.
 */
function amountsLabel(row: WarehouseReportRowResponse): string {
  const parts: string[] = []
  if (row.amountPEN !== 0) parts.push(formatCurrency(row.amountPEN, 'PEN'))
  if (row.amountUSD !== 0) parts.push(formatCurrency(row.amountUSD, 'USD'))
  return parts.length > 0 ? parts.join(' · ') : formatCurrency(0, 'PEN')
}

/**
 * El `detail` de la fila es texto libre del backend salvo en el corte por
 * semana, donde es la fecha ISO del lunes: ahí se formatea a dd/mm/aaaa.
 */
function detailLabel(row: WarehouseReportRowResponse, cut: WarehouseReportResponse['cut']): string | null {
  if (!row.detail) return null
  return cut === 'BY_PERIOD' ? formatDateOnly(row.detail) : row.detail
}

/**
 * Ranking del reporte: una fila por grupo, con sus montos por moneda y una barra
 * proporcional. No es una `DataTable` porque el endpoint no pagina y la fila es
 * bi-moneda con barra, que la tabla genérica no modela.
 *
 * Las filas se muestran en el orden en que llegan (monto DESC, salvo el corte
 * por semana que viene cronológico): el orden es parte de la respuesta.
 */
export function ReportRowsList({ report }: ReportRowsListProps) {
  const meta = reportCutMeta(report.cut)
  // `reduce` y no `Math.max(...spread)`: el endpoint no pagina y un corte por
  // producto sobre un rango anual puede traer muchas filas.
  const maxWeight = report.rows.reduce((max, row) => Math.max(max, rowWeight(row)), 1)

  return (
    <Card as="section" padding="none">
      <h2 className="border-b border-border px-5 py-4 text-xs font-semibold uppercase tracking-wide text-fg-muted">
        {meta.label}
      </h2>
      <ul className="divide-y divide-border">
        {report.rows.map((row, index) => {
          const amounts = amountsLabel(row)
          const detail = detailLabel(row, report.cut)
          return (
            <li key={`${row.label}-${index}`} className="px-5 py-3.5">
              <div className="flex items-baseline justify-between gap-4">
                <p className="text-sm font-medium text-fg">
                  {row.label}
                  {detail && (
                    <span className="ml-2 text-xs font-normal text-fg-subtle">{detail}</span>
                  )}
                </p>
                <p className="shrink-0 text-sm font-semibold tabular-nums text-fg">
                  {amounts}
                  <span className="ml-2 text-xs font-normal text-fg-muted">
                    {formatQuantity(row.count)} {meta.countLabel}
                  </span>
                </p>
              </div>
              {/* Decorativa: repite montos que ya están en texto dos líneas
                  arriba, así que se oculta en vez de anunciarse por segunda vez. */}
              <div
                className="mt-2 h-2 overflow-hidden rounded-full bg-surface-muted"
                aria-hidden="true"
              >
                <div
                  className="h-full rounded-full bg-accent"
                  // El mínimo del 3% hace visible una fila de monto chico, pero
                  // el cero se dibuja vacío: un producto sin precio de compra de
                  // referencia no debe verse igual que uno con monto.
                  style={{
                    width:
                      rowWeight(row) === 0
                        ? '0%'
                        : `${Math.max(3, (rowWeight(row) / maxWeight) * 100)}%`,
                  }}
                />
              </div>
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
