import { useRef } from 'react'
import { CalendarRange } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import { todayIsoDate } from '../../../shared/utils/formatters'
import {
  currentMonthStart,
  isReportRangeIncomplete,
  isReportRangeInverted,
  type ReportFilters,
} from '../schemas/report-filters.schema'
import { REPORT_CUTS } from '../utils/reportCuts'
import { Button } from '../../../shared/ui/Button'
import { Card } from '../../../shared/ui/Card'
import { fieldClasses } from '../../../shared/ui/fieldClasses'

interface ReportFilterBarProps {
  value: ReportFilters
  onChange: (next: ReportFilters) => void
}

// Sin `w-full`: en esta barra el control va en una fila de ancho fijo y estirarlo
// cambiaría el ancho de los dos campos de fecha.
const inputClasses = fieldClasses({ density: 'compact' })

/**
 * Corte y rango del reporte. Los cortes son un `tablist`: son cuatro vistas del
 * mismo panel de resultados, no cuatro filtros acumulables.
 *
 * El rango inválido (invertido o incompleto) se avisa acá y la página no
 * consulta hasta corregirlo. Son dos mensajes distintos a propósito: borrar una
 * fecha y ponerlas al revés son errores distintos y se arreglan distinto.
 */
export function ReportFilterBar({ value, onChange }: ReportFilterBarProps) {
  function set<K extends keyof ReportFilters>(key: K, fieldValue: ReportFilters[K]) {
    onChange({ ...value, [key]: fieldValue })
  }

  const inverted = isReportRangeInverted(value)
  const incomplete = isReportRangeIncomplete(value)
  const rangeMessage = incomplete
    ? 'Completa ambas fechas para generar el reporte.'
    : inverted
      ? 'La fecha "desde" no puede ser posterior a la fecha "hasta".'
      : null

  const tabRefs = useRef<(HTMLButtonElement | null)[]>([])

  function handleCutKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, index: number) {
    // Navegación por flechas dentro del tablist (WAI-ARIA): sin esto el usuario
    // de teclado tendría que tabular por cada corte para llegar al último.
    const last = REPORT_CUTS.length - 1
    const nextIndex =
      event.key === 'ArrowRight'
        ? (index + 1) % REPORT_CUTS.length
        : event.key === 'ArrowLeft'
          ? (index - 1 + REPORT_CUTS.length) % REPORT_CUTS.length
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? last
              : -1
    if (nextIndex < 0) return
    event.preventDefault()
    set('cut', REPORT_CUTS[nextIndex].value)
    // El foco DEBE seguir a la selección: con roving tabindex el botón que se
    // deja atrás pasa a `tabIndex={-1}`, así que un foco parado ahí rompe tanto
    // la flecha siguiente (calcularía desde el índice viejo) como el Tab.
    tabRefs.current[nextIndex]?.focus()
  }

  return (
    <Card padding="md" className="space-y-4">
      <div className="flex flex-wrap gap-2" role="tablist" aria-label="Corte del reporte">
        {REPORT_CUTS.map((option, index) => {
          const selected = value.cut === option.value
          return (
            <button
              key={option.value}
              id={`report-tab-${option.value}`}
              ref={(node) => {
                tabRefs.current[index] = node
              }}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls="report-panel"
              tabIndex={selected ? 0 : -1}
              onClick={() => set('cut', option.value)}
              onKeyDown={(event) => handleCutKeyDown(event, index)}
              className={cn(
                'rounded-lg border px-3.5 py-2 text-sm font-medium transition focus:outline-none focus-visible:ring-2 focus-visible:ring-focus',
                selected
                  ? 'border-accent bg-accent text-on-solid'
                  : 'border-border-strong bg-surface text-fg-body hover:bg-surface-subtle',
              )}
            >
              {option.label}
            </button>
          )
        })}
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label
            htmlFor="report-date-from"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            Desde
          </label>
          <input
            id="report-date-from"
            type="date"
            value={value.dateFrom}
            onChange={(event) => set('dateFrom', event.target.value)}
            aria-invalid={!!rangeMessage}
            aria-describedby={rangeMessage ? 'report-date-error' : undefined}
            className={inputClasses}
          />
        </div>
        <div>
          <label
            htmlFor="report-date-to"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            Hasta
          </label>
          <input
            id="report-date-to"
            type="date"
            value={value.dateTo}
            onChange={(event) => set('dateTo', event.target.value)}
            aria-invalid={!!rangeMessage}
            aria-describedby={rangeMessage ? 'report-date-error' : undefined}
            className={inputClasses}
          />
        </div>
        <Button
          variant="secondary"
          onClick={() =>
            onChange({ ...value, dateFrom: currentMonthStart(), dateTo: todayIsoDate() })
          }
        >
          <CalendarRange className="mr-2 h-4 w-4" aria-hidden="true" />
          Mes actual
        </Button>
      </div>

      {rangeMessage && (
        <p id="report-date-error" role="alert" className="text-xs text-danger">
          {rangeMessage}
        </p>
      )}
    </Card>
  )
}
