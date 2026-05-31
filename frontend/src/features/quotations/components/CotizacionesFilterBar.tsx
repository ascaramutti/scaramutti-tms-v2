import { Search } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import {
  isValidDateRange,
  QUOTATION_STATUS_VALUES,
  QUOTATION_TYPE_VALUES,
  type QuotationFilters,
} from '../schemas/quotation-filters.schema'
import { QUOTATION_STATUS_LABELS, QUOTATION_TYPE_LABELS } from '../utils/quotationLabels'
import { SEARCH_MIN_LENGTH } from '../hooks/useQuotationsList'

interface CotizacionesFilterBarProps {
  value: QuotationFilters
  onChange: (next: QuotationFilters) => void
}

const inputClasses =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Barra de filtros del listado de cotizaciones: búsqueda libre + estado + tipo
 * + rango de fechas. El debounce de la búsqueda lo aplica la página (este
 * componente solo refleja y propaga el estado).
 */
export function CotizacionesFilterBar({ value, onChange }: CotizacionesFilterBarProps) {
  function set<K extends keyof QuotationFilters>(key: K, fieldValue: QuotationFilters[K]) {
    onChange({ ...value, [key]: fieldValue })
  }

  const qLength = value.q.trim().length
  const showSearchHint = qLength > 0 && qLength < SEARCH_MIN_LENGTH
  const dateRangeInvalid = !isValidDateRange(value.dateFrom, value.dateTo)

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-6">
        {/* Búsqueda libre */}
        <div className="lg:col-span-2">
          <label htmlFor="q" className="mb-1.5 block text-sm font-medium text-slate-700">
            Buscar
          </label>
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
              aria-hidden="true"
            />
            <input
              id="q"
              type="search"
              value={value.q}
              onChange={(event) => set('q', event.target.value)}
              placeholder="Código, cliente, RUC, origen, destino…"
              aria-describedby={showSearchHint ? 'q-hint' : undefined}
              className={cn(inputClasses, 'pl-9 pr-3 placeholder:text-slate-400')}
            />
          </div>
          {showSearchHint && (
            <p id="q-hint" className="mt-1 text-xs text-slate-500">
              Ingresá al menos {SEARCH_MIN_LENGTH} caracteres para buscar.
            </p>
          )}
        </div>

        {/* Estado */}
        <div>
          <label htmlFor="status" className="mb-1.5 block text-sm font-medium text-slate-700">
            Estado
          </label>
          <select
            id="status"
            value={value.status ?? ''}
            onChange={(event) =>
              set('status', (event.target.value || undefined) as QuotationFilters['status'])
            }
            className={inputClasses}
          >
            <option value="">Todos</option>
            {QUOTATION_STATUS_VALUES.map((statusValue) => (
              <option key={statusValue} value={statusValue}>
                {QUOTATION_STATUS_LABELS[statusValue]}
              </option>
            ))}
          </select>
        </div>

        {/* Tipo */}
        <div>
          <label htmlFor="quotationType" className="mb-1.5 block text-sm font-medium text-slate-700">
            Tipo
          </label>
          <select
            id="quotationType"
            value={value.quotationType ?? ''}
            onChange={(event) =>
              set(
                'quotationType',
                (event.target.value || undefined) as QuotationFilters['quotationType'],
              )
            }
            className={inputClasses}
          >
            <option value="">Todos</option>
            {QUOTATION_TYPE_VALUES.map((typeValue) => (
              <option key={typeValue} value={typeValue}>
                {QUOTATION_TYPE_LABELS[typeValue]}
              </option>
            ))}
          </select>
        </div>

        {/* Fecha desde */}
        <div>
          <label htmlFor="dateFrom" className="mb-1.5 block text-sm font-medium text-slate-700">
            Desde
          </label>
          <input
            id="dateFrom"
            type="date"
            value={value.dateFrom ?? ''}
            onChange={(event) => set('dateFrom', event.target.value || undefined)}
            className={inputClasses}
          />
        </div>

        {/* Fecha hasta */}
        <div>
          <label htmlFor="dateTo" className="mb-1.5 block text-sm font-medium text-slate-700">
            Hasta
          </label>
          <input
            id="dateTo"
            type="date"
            value={value.dateTo ?? ''}
            onChange={(event) => set('dateTo', event.target.value || undefined)}
            aria-invalid={dateRangeInvalid}
            aria-describedby={dateRangeInvalid ? 'dateTo-error' : undefined}
            className={cn(
              inputClasses,
              dateRangeInvalid && 'border-red-300 focus:border-red-500 focus:ring-red-500',
            )}
          />
          {dateRangeInvalid && (
            <p id="dateTo-error" role="alert" className="mt-1 text-xs text-red-600">
              No puede ser anterior a "Desde".
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
