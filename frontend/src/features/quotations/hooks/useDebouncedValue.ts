import { useEffect, useState } from 'react'

/**
 * Devuelve `value` con un retraso de `delayMs` desde su último cambio. Evita
 * disparar una request por cada tecla en inputs de búsqueda.
 *
 * Local a cotizaciones por ahora; extraer a `shared/` cuando otro módulo
 * (ej. el listado de clientes) lo necesite.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}
