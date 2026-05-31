import { useEffect, useState } from 'react'

/**
 * Devuelve `value` con un retraso de `delayMs` desde su último cambio. Evita
 * disparar una request por cada tecla en inputs de búsqueda (listado de
 * cotizaciones, combobox de clientes del wizard, etc.).
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}
