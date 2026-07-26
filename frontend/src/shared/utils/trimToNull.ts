/**
 * Texto opcional de un formulario al valor que espera el contrato: un campo que
 * el usuario dejó vacío (o con solo espacios) es "sin valor", que en JSON es
 * `null` y no una cadena vacía.
 */
export function trimToNull(value: string | undefined | null): string | null {
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}
