import { Search } from 'lucide-react'
import { cn } from '../../../shared/utils/cn'
import { Card } from '../../../shared/ui/Card'
import { FIELD_LABEL, fieldClasses } from '../../../shared/ui/fieldClasses'
import {
  REGISTRY_SEARCH_MAX_LENGTH,
  REGISTRY_SEARCH_MIN_LENGTH,
} from '../hooks/useWorkerRegistrySearch'
import { WORKER_STATUS_FILTER_OPTIONS, type WorkerStatusFilter } from '../workerStatusFilter'

interface WorkerSearchBarProps {
  query: string
  onQueryChange: (next: string) => void
  status: WorkerStatusFilter
  onStatusChange: (next: WorkerStatusFilter) => void
}

const inputClasses = cn('w-full', fieldClasses({ density: 'compact' }))

/**
 * Buscador del padrón. La pista del mínimo se ve también con el campo vacío,
 * como en clientes: sin listado inicial, es la única instrucción de la pantalla.
 */
export function WorkerSearchBar({
  query,
  onQueryChange,
  status,
  onStatusChange,
}: WorkerSearchBarProps) {
  const showHint = query.trim().length < REGISTRY_SEARCH_MIN_LENGTH

  return (
    <Card padding="md">
      <div className="grid gap-4 sm:grid-cols-[1fr_12rem]">
        <div>
          <label htmlFor="q" className={FIELD_LABEL}>
            Buscar trabajador
          </label>
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-subtle"
              aria-hidden="true"
            />
            <input
              id="q"
              type="search"
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              maxLength={REGISTRY_SEARCH_MAX_LENGTH}
              placeholder="Nombre, apellido o documento"
              aria-describedby={showHint ? 'q-hint' : undefined}
              className={cn(inputClasses, 'pl-9 pr-3 placeholder:text-fg-subtle')}
            />
          </div>
          {showHint && (
            <p id="q-hint" className="mt-1 text-xs text-fg-muted">
              Ingresa al menos {REGISTRY_SEARCH_MIN_LENGTH} caracteres para buscar.
            </p>
          )}
        </div>
        <div>
          <label htmlFor="status" className={FIELD_LABEL}>
            Estado
          </label>
          <select
            id="status"
            value={status}
            onChange={(event) => onStatusChange(event.target.value as WorkerStatusFilter)}
            className={inputClasses}
          >
            {WORKER_STATUS_FILTER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>
    </Card>
  )
}
