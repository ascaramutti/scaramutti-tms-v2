import type { WarehouseReportResponse } from '../../../api'
import { formatCurrency, formatQuantity } from '../../../shared/utils/formatters'
import { reportCutMeta } from '../utils/reportCuts'

interface ReportTotalsCardsProps {
  report: WarehouseReportResponse
}

const CARD_CLASSES = 'rounded-xl border border-slate-200 bg-white p-5 shadow-sm'

/**
 * Totales del reporte, tal como los devolvió el backend (no se recalculan desde
 * las filas). Soles y dólares van en tarjetas separadas: RN-WH7 manda agregar
 * por moneda SIN convertir, así que no existe un "total general".
 */
export function ReportTotalsCards({ report }: ReportTotalsCardsProps) {
  const meta = reportCutMeta(report.cut)
  return (
    <div
      role="group"
      aria-label="Totales del reporte"
      className="grid grid-cols-1 gap-4 sm:grid-cols-3"
    >
      <div className={CARD_CLASSES}>
        <p className="text-2xl font-semibold tabular-nums text-slate-900">
          {formatCurrency(report.totalPEN, 'PEN')}
        </p>
        <p className="mt-0.5 text-sm text-slate-500">{meta.amountLabel('soles')}</p>
      </div>
      <div className={CARD_CLASSES}>
        <p className="text-2xl font-semibold tabular-nums text-slate-900">
          {formatCurrency(report.totalUSD, 'USD')}
        </p>
        <p className="mt-0.5 text-sm text-slate-500">{meta.amountLabel('dólares')}</p>
      </div>
      <div className={CARD_CLASSES}>
        <p className="text-2xl font-semibold tabular-nums text-slate-900">
          {formatQuantity(report.totalCount)}
        </p>
        <p className="mt-0.5 text-sm text-slate-500">Total de {meta.countLabel}</p>
      </div>
    </div>
  )
}
