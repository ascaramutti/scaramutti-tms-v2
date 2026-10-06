import { Link } from 'react-router-dom'
import { UserPlus } from 'lucide-react'
import { useState } from 'react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { buttonClasses } from '../../../shared/ui/buttonClasses'
import { WORKERS_BASE } from '../../../shared/paths'
import { cn } from '../../../shared/utils/cn'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { WorkerSearchBar } from '../components/WorkerSearchBar'
import { WorkerSearchResults } from '../components/WorkerSearchResults'
import {
  REGISTRY_SEARCH_MIN_LENGTH,
  useWorkerRegistrySearch,
} from '../hooks/useWorkerRegistrySearch'
import {
  WORKER_STATUS_FILTER_DEFAULT,
  isActiveParam,
  type WorkerStatusFilter,
} from '../workerStatusFilter'

/** Espera desde la última tecla antes de buscar; los tests derivan de acá sus esperas. */
export const SEARCH_DEBOUNCE_MS = 300

// El anuncio nombra el estado: al cambiar el filtro con el mismo número de filas,
// un texto idéntico no se vuelve a leer y el cambio pasaría en silencio.
const STATUS_WORDS: Record<WorkerStatusFilter, readonly [string, string] | null> = {
  active: ['activo', 'activos'],
  inactive: ['inactivo', 'inactivos'],
  all: null,
}

function resultsAnnouncement(count: number, status: WorkerStatusFilter): string {
  const words = STATUS_WORDS[status]
  if (count === 0) return words ? `Sin trabajadores ${words[1]}` : 'Sin resultados'
  const noun = count === 1 ? 'trabajador' : 'trabajadores'
  return words ? `${count} ${noun} ${words[count === 1 ? 0 : 1]}` : `${count} ${noun}`
}

/**
 * Padrón de trabajadores: buscar para ver quién está. No lista al abrir, como
 * clientes: se entra sabiendo a quién se busca.
 */
export function WorkersSearchPage() {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<WorkerStatusFilter>(WORKER_STATUS_FILTER_DEFAULT)
  const debounced = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isLoading, isFetching, isPlaceholderData, isError, error, refetch } =
    useWorkerRegistrySearch(
      debounced,
      isActiveParam(status),
    )

  const isSearching = debounced.trim().length >= REGISTRY_SEARCH_MIN_LENGTH
  const results = data ?? []
  const showResults = isSearching && !isLoading && !isError
  // Mientras llega la respuesta a una búsqueda nueva (texto o filtro), las filas son las de la
  // anterior: no se anuncian.
  const announce = showResults && !isPlaceholderData

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Trabajadores"
        description="Busca por nombre, apellido o documento para ver quién está en el padrón."
        divider
        action={
          <Link to={`${WORKERS_BASE}/nuevo`} className={cn(buttonClasses({ variant: 'primary' }), 'gap-1.5')}>
            <UserPlus className="h-4 w-4" aria-hidden="true" />
            Nuevo trabajador
          </Link>
        }
      />

      <WorkerSearchBar
        query={query}
        onQueryChange={setQuery}
        status={status}
        onStatusChange={setStatus}
      />

      {isSearching && (
        <WorkerSearchResults
          workers={results}
          isLoading={isLoading}
          isPlaceholderData={isPlaceholderData}
          isFetching={isFetching}
          isError={isError}
          errorMessage={getApiErrorMessage(
            error,
            'No se pudieron buscar trabajadores. Intenta de nuevo.',
          )}
          onRetry={() => void refetch()}
        />
      )}

      {/* Siempre montada, incluso vacía: una región viva que entra al árbol junto
          con su texto no la anuncia ningún lector de pantalla. */}
      <p className="sr-only" aria-live="polite">
        {announce ? resultsAnnouncement(results.length, status) : ''}
      </p>
    </div>
  )
}
