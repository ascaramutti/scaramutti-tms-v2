import type { QueryClient } from '@tanstack/react-query'

/** Todo lo que el navegador guarda fuera de la pantalla, en un texto: un secreto no tiene que estar en él. */
export function browserLeftovers(queryClient: QueryClient, location: unknown, notices: unknown): string {
  return JSON.stringify({
    ruta: location,
    queries: queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state]),
    mutations: queryClient.getMutationCache().getAll().map((mutation) => mutation.state),
    href: window.location.href,
    history: window.history.state,
    local: entriesOf(localStorage),
    session: entriesOf(sessionStorage),
    avisos: notices,
  })
}

// Recorrido por clave: el almacenamiento de los tests guarda sus datos en un campo privado, que copiarlo no ve.
function entriesOf(storage: Storage): Record<string, string | null> {
  return Object.fromEntries(Array.from({ length: storage.length }, (_, index) => storage.key(index) ?? '').map((key) => [key, storage.getItem(key)]))
}
