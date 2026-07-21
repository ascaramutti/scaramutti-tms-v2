/**
 * Recurso + el ETag OPACO del header de la respuesta, para el `If-Match` de su PUT.
 *
 * El `_etag` NO sale del body: el campo `updatedAt` del JSON NO sirve como If-Match porque su
 * serialización difiere del ETag que el backend compara (p.ej. Jackson recorta un cero final de
 * los microsegundos → `.39289Z` en el body vs `.392890Z` en el ETag → 412 espurio). Según HTTP el
 * ETag es opaco: hay que reenviar el valor del header tal cual, sin reconstruirlo.
 */
export type WithEtag<T> = T & { _etag: string | null }

/**
 * Lee el header `ETag` de la respuesta, o `null` si no vino. Prueba las dos casing que se dan en
 * la práctica: el adapter del browser lo baja a `etag`; en Node/tests (MSW) queda `ETag`.
 */
export function readEtag(headers: unknown): string | null {
  if (!headers || typeof headers !== 'object') return null
  const record = headers as Record<string, unknown>
  const value = record.etag ?? record.ETag
  return typeof value === 'string' ? value : null
}
