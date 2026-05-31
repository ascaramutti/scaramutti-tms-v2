/**
 * Formatea un monto con su moneda en formato es-PE.
 * `currencyCode` es ISO 4217 (PEN, USD — las únicas monedas del sistema).
 * Produce "S/ 1,500.50" para PEN y "US$ 2,000.00" para USD.
 */
export function formatCurrency(amount: number, currencyCode: string): string {
  try {
    return new Intl.NumberFormat('es-PE', {
      style: 'currency',
      currency: currencyCode,
    }).format(amount)
  } catch {
    // `Intl.NumberFormat` lanza `RangeError` si `currencyCode` no es ISO 4217
    // válido. El contrato lo tipa como `string` (no enum), así que ante un
    // código inesperado degradamos a un formato neutro en vez de tumbar la fila.
    return `${currencyCode} ${amount.toFixed(2)}`
  }
}

/**
 * Formatea una fecha ISO (UTC) a formato corto dd/mm/aaaa en zona horaria de
 * Lima. El backend interpreta las fechas en America/Lima (UTC-5); fijamos el
 * `timeZone` para que un `createdAt` cerca de medianoche no muestre el día
 * anterior/siguiente.
 */
export function formatDate(isoDate: string): string {
  return new Intl.DateTimeFormat('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    timeZone: 'America/Lima',
  }).format(new Date(isoDate))
}
