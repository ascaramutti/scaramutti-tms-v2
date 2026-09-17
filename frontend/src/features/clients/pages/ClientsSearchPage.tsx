import { useState } from 'react'
import { Button } from '../../../shared/ui/Button'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { ClientSearchBar } from '../components/ClientSearchBar'
import { ClientSearchResults } from '../components/ClientSearchResults'
import { CLIENT_SEARCH_MIN_LENGTH, useClientsSearch } from '../hooks/useClientsSearch'

/**
 * Maestro de clientes: buscar para corregir.
 *
 * No lista al abrir ni pagina: se entra sabiendo a quién se busca. Y no ofrece
 * dar de alta, que sigue siendo al vuelo desde el asistente de cotizaciones y
 * desde el alta de servicios.
 *
 * Busca solo entre los activos. Un cliente desactivado se corrige recién cuando
 * exista la reactivación, que no es parte de esta unidad.
 */
export function ClientsSearchPage() {
  const [query, setQuery] = useState('')
  const debounced = useDebouncedValue(query, 300)
  const { data, isLoading, isFetching, isError, error, refetch } = useClientsSearch(debounced, {
    isActive: true,
  })

  const isSearching = debounced.trim().length >= CLIENT_SEARCH_MIN_LENGTH
  const results = data?.content ?? []
  const total = data?.totalElements ?? 0
  const hasMoreThanShown = total > results.length
  const showResults = isSearching && !isLoading && !isError

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Clientes"
        description="Busca por razón social o RUC para corregir los datos de un cliente."
        divider
      />

      <ClientSearchBar value={query} onChange={setQuery} />

      {isSearching && isLoading && (
        <div className="flex justify-center py-10">
          <Spinner size={28} label="Buscando clientes" className="text-accent" />
        </div>
      )}

      {isSearching && isError && (
        <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
          <p className="text-sm text-fg-body">
            {getApiErrorMessage(error, 'No se pudieron buscar clientes. Intenta de nuevo.')}
          </p>
          <Button variant="secondary" onClick={() => void refetch()}>
            Reintentar
          </Button>
        </div>
      )}

      {showResults && results.length === 0 && (
        <EmptyState
          title="No encontramos clientes con ese texto"
          description="Revisa la escritura o prueba con el RUC. Los clientes desactivados no aparecen acá."
        />
      )}

      {showResults && results.length > 0 && (
        <div aria-busy={isFetching} className={isFetching ? 'opacity-60' : undefined}>
          <ClientSearchResults clients={results} total={total} />
        </div>
      )}

      {/* SIEMPRE montada, incluso vacía. Una región viva que entra al árbol junto
          con su texto no la anuncia ningún lector de pantalla: tiene que estar
          antes de que el texto cambie, o la primera búsqueda pasa en silencio.
          Y lo que anuncia son las COINCIDENCIAS, no las filas visibles: decir
          "diez" cuando hay cuatrocientas hace creer que están todas, y esconde
          la única instrucción que saca del pozo. */}
      <p className="sr-only" aria-live="polite">
        {!showResults
          ? ''
          : results.length === 0
            ? 'Sin resultados'
            : hasMoreThanShown
              ? `Se muestran los primeros ${results.length} de ${total}. Escribe más letras del nombre o el RUC completo.`
              : `${results.length} ${results.length === 1 ? 'cliente' : 'clientes'}`}
      </p>
    </div>
  )
}
