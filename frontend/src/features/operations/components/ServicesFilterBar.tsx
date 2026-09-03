import { Search } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import type { ServiceStatus } from '../../../api'
import {
  SERVICE_STATUS_PRESENTATION,
  SERVICE_STATUS_VALUES,
} from '../status/serviceStatusPresentation'
import {
  hasSearchableToken,
  isValidDateRange,
  SERVICE_DATE_MAX,
  SERVICE_DATE_MIN,
  SERVICE_SEARCH_MAX_LENGTH,
  SERVICE_SEARCH_MIN_LENGTH,
  type ServiceFilters,
} from '../schemas/service-filters.schema'
import { Card } from '../../../shared/ui/Card'
import { fieldClasses } from '../../../shared/ui/fieldClasses'

interface ServicesFilterBarProps {
  value: ServiceFilters
  onChange: (next: ServiceFilters) => void
}

const inputClasses = cn('w-full', fieldClasses({ density: 'compact' }))

/** El rango inválido se marca en los dos inputs, no solo con el texto al pie. */
const invalidInputClasses = 'border-danger-border-strong focus:border-danger focus:ring-danger'

/**
 * Filtros del listado de servicios: búsqueda libre, estado y rango de fecha
 * tentativa. El debounce de la búsqueda lo aplica la página (este componente
 * solo refleja y propaga).
 *
 * Un rango invertido no se manda al backend: el contrato no define ese 400 para
 * este listado, así que devolvería una página vacía indistinguible de "no hay
 * servicios". Se avisa acá y la consulta sale SIN filtro de fechas hasta que el
 * rango vuelva a tener sentido.
 *
 * Las fechas no se topean a hoy: el contrato admite fecha tentativa pasada
 * (registro retroactivo) y futura, así que capar el input escondería viajes.
 */
export function ServicesFilterBar({ value, onChange }: ServicesFilterBarProps) {
  function set<K extends keyof ServiceFilters>(key: K, fieldValue: ServiceFilters[K]) {
    onChange({ ...value, [key]: fieldValue })
  }

  const trimmedQ = value.q.trim()
  // El hint aparece cuando hay texto pero ninguna palabra alcanza el mínimo que el
  // backend indexa: "de la" tiene 5 caracteres y no busca nada.
  const showSearchHint = trimmedQ.length > 0 && !hasSearchableToken(trimmedQ)
  const invalidRange = !isValidDateRange(value.dateFrom, value.dateTo)

  return (
    <Card padding="md">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-6">
        <div className="lg:col-span-2">
          <label htmlFor="services-q" className="mb-1.5 block text-sm font-medium text-fg-body">
            Buscar
          </label>
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-subtle"
              aria-hidden="true"
            />
            <input
              id="services-q"
              type="search"
              value={value.q}
              maxLength={SERVICE_SEARCH_MAX_LENGTH}
              onChange={(event) => set('q', event.target.value)}
              placeholder="Código, cliente, RUC, origen, destino…"
              aria-describedby={showSearchHint ? 'services-q-hint' : undefined}
              className={cn(inputClasses, 'pl-9 pr-3 placeholder:text-fg-subtle')}
            />
          </div>
          {showSearchHint && (
            <p id="services-q-hint" className="mt-1 text-xs text-fg-muted">
              Ingresa al menos una palabra de {SERVICE_SEARCH_MIN_LENGTH} caracteres para buscar.
            </p>
          )}
        </div>

        {/* Dos columnas: "Todos (sin eliminados)" no entra en una y el select
            recorta la opción en vez de ensancharse. */}
        <div className="lg:col-span-2">
          <label
            htmlFor="services-status"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            Estado
          </label>
          <select
            id="services-status"
            value={value.status ?? ''}
            onChange={(event) =>
              set('status', event.target.value ? (event.target.value as ServiceStatus) : undefined)
            }
            className={inputClasses}
          >
            {/* El contrato esconde los eliminados salvo que se pidan por su estado,
                así que "Todos" son cinco de seis: decirlo evita leer una lista sin
                bajas como prueba de que no hubo ninguna. */}
            <option value="">Todos (sin eliminados)</option>
            {SERVICE_STATUS_VALUES.map((status) => (
              <option key={status} value={status}>
                {SERVICE_STATUS_PRESENTATION[status].label}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-2 lg:col-span-2">
          <div>
            <label
              htmlFor="services-date-from"
              className="mb-1.5 block text-sm font-medium text-fg-body"
            >
              Desde
            </label>
            <input
              id="services-date-from"
              type="date"
              value={value.dateFrom ?? ''}
              min={SERVICE_DATE_MIN}
              max={SERVICE_DATE_MAX}
              onChange={(event) => set('dateFrom', event.target.value || undefined)}
              aria-invalid={invalidRange}
              aria-describedby={invalidRange ? 'services-date-error' : undefined}
              className={cn(inputClasses, invalidRange && invalidInputClasses)}
            />
          </div>
          <div>
            <label
              htmlFor="services-date-to"
              className="mb-1.5 block text-sm font-medium text-fg-body"
            >
              Hasta
            </label>
            <input
              id="services-date-to"
              type="date"
              value={value.dateTo ?? ''}
              min={SERVICE_DATE_MIN}
              max={SERVICE_DATE_MAX}
              onChange={(event) => set('dateTo', event.target.value || undefined)}
              aria-invalid={invalidRange}
              aria-describedby={invalidRange ? 'services-date-error' : undefined}
              className={cn(inputClasses, invalidRange && invalidInputClasses)}
            />
          </div>
        </div>
      </div>

      {invalidRange && (
        <p id="services-date-error" role="alert" className="mt-3 text-xs text-danger">
          La fecha "desde" no puede ser posterior a la fecha "hasta": el filtro de
          fechas no se está aplicando.
        </p>
      )}
    </Card>
  )
}
