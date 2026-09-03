import { Search } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import { ENTRY_SEARCH_MIN_LENGTH } from '../hooks/useWarehousePurchaseInvoicesList'
import { ENTRY_SEARCH_MAX_LENGTH, type EntryFilters } from '../schemas/entry-filters.schema'
import { Card } from '../../../shared/ui/Card'

interface EntriesFilterBarProps {
  value: EntryFilters
  onChange: (next: EntryFilters) => void
}

const inputClasses =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Filtros del listado de entradas: búsqueda libre, estado y rango de fechas de
 * factura. El debounce de la búsqueda lo aplica la página (este componente solo
 * refleja y propaga).
 *
 * Un rango invertido no se manda al backend: el contrato no define ese 400 para
 * este listado, así que devolvería un resultado vacío indistinguible de "no hay
 * facturas". Se avisa acá y la consulta sale SIN filtro de fechas hasta que el
 * rango vuelva a tener sentido.
 */
export function EntriesFilterBar({ value, onChange }: EntriesFilterBarProps) {
  function set<K extends keyof EntryFilters>(key: K, fieldValue: EntryFilters[K]) {
    onChange({ ...value, [key]: fieldValue })
  }

  const qLength = value.q.trim().length
  const showSearchHint = qLength > 0 && qLength < ENTRY_SEARCH_MIN_LENGTH
  const invalidRange = !!value.dateFrom && !!value.dateTo && value.dateFrom > value.dateTo

  return (
    <Card padding="md">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-12">
        <div className="lg:col-span-6">
          <label htmlFor="entries-q" className="mb-1.5 block text-sm font-medium text-slate-700">
            Buscar
          </label>
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
              aria-hidden="true"
            />
            <input
              id="entries-q"
              type="search"
              value={value.q}
              maxLength={ENTRY_SEARCH_MAX_LENGTH}
              onChange={(event) => set('q', event.target.value)}
              placeholder="N° de factura, guía, proveedor…"
              aria-describedby={showSearchHint ? 'entries-q-hint' : undefined}
              className={cn(inputClasses, 'pl-9 pr-3 placeholder:text-slate-400')}
            />
          </div>
          {showSearchHint && (
            <p id="entries-q-hint" className="mt-1 text-xs text-slate-500">
              Ingresa al menos {ENTRY_SEARCH_MIN_LENGTH} caracteres para buscar.
            </p>
          )}
        </div>

        <div className="lg:col-span-2">
          <label
            htmlFor="entries-status"
            className="mb-1.5 block text-sm font-medium text-slate-700"
          >
            Estado
          </label>
          <select
            id="entries-status"
            value={value.status ?? ''}
            onChange={(event) =>
              set('status', event.target.value ? (event.target.value as 'ACTIVE' | 'CANCELLED') : undefined)
            }
            className={inputClasses}
          >
            <option value="">Todos</option>
            <option value="ACTIVE">Activas</option>
            <option value="CANCELLED">Anuladas</option>
          </select>
        </div>

        <div className="grid grid-cols-2 gap-2 lg:col-span-4">
          <div>
            <label
              htmlFor="entries-date-from"
              className="mb-1.5 block text-sm font-medium text-slate-700"
            >
              Desde
            </label>
            <input
              id="entries-date-from"
              type="date"
              value={value.dateFrom ?? ''}
              onChange={(event) => set('dateFrom', event.target.value || undefined)}
              aria-invalid={invalidRange}
              aria-describedby={invalidRange ? 'entries-date-error' : undefined}
              className={inputClasses}
            />
          </div>
          <div>
            <label
              htmlFor="entries-date-to"
              className="mb-1.5 block text-sm font-medium text-slate-700"
            >
              Hasta
            </label>
            <input
              id="entries-date-to"
              type="date"
              value={value.dateTo ?? ''}
              onChange={(event) => set('dateTo', event.target.value || undefined)}
              aria-invalid={invalidRange}
              aria-describedby={invalidRange ? 'entries-date-error' : undefined}
              className={inputClasses}
            />
          </div>
        </div>
      </div>

      {invalidRange && (
        <p id="entries-date-error" role="alert" className="mt-3 text-xs text-red-600">
          La fecha "desde" no puede ser posterior a la fecha "hasta".
        </p>
      )}
    </Card>
  )
}
