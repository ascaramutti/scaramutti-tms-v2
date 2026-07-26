import { useState } from 'react'

/**
 * Retiene la última página cargada con éxito de un listado paginado.
 *
 * react-query descarta `data` al entrar en error (`placeholderData` solo aplica
 * en estado pending), así que un refetch fallido al paginar o filtrar vaciaría
 * la tabla. Conservando la última página buena, la pantalla muestra la data
 * previa con un aviso no destructivo en lugar del error a pantalla completa.
 *
 * Ajusta estado durante el render (patrón recomendado por React en vez de un
 * `useEffect`): es condicional y converge, porque en el re-render siguiente
 * `data === lastGoodPage`.
 */
export function useLastGoodPage<T>(data: T | undefined): T | undefined {
  const [lastGoodPage, setLastGoodPage] = useState<T | undefined>(undefined)
  if (data && data !== lastGoodPage) {
    setLastGoodPage(data)
  }
  return data ?? lastGoodPage
}
