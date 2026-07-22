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

/**
 * Formatea una fecha *sin hora* (`YYYY-MM-DD`, ej. `tentativeServiceDate`) a
 * dd/mm/aaaa. A diferencia de `formatDate`, NO usa zona horaria: `new Date(iso)`
 * interpretaría un date-only como UTC medianoche y en Lima (UTC-5) retrocedería
 * al día anterior. Acá construimos la fecha en horario local para que el día sea
 * exactamente el del string.
 */
export function formatDateOnly(isoDate: string): string {
  const [year, month, day] = isoDate.slice(0, 10).split('-').map(Number)
  return new Intl.DateTimeFormat('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(year, month - 1, day))
}

/**
 * Fecha de hoy como `YYYY-MM-DD` en la zona del navegador, para comparar contra
 * los date-only del contrato (`invoiceDate`) y para el `max` de los inputs de
 * fecha. Se arma con los componentes locales a propósito: `toISOString()` pasa
 * por UTC y en Lima (UTC-5) devolvería el día siguiente después de las 19:00.
 */
export function todayIsoDate(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/**
 * Formatea una cantidad de inventario en formato es-PE (separador de miles).
 * El contrato tipa stock y mínimos como `number`, así que admite decimales
 * (unidades como litros o galones): se muestran hasta 2, sin rellenar con
 * ceros las cantidades enteras ("1,250" y no "1,250.00").
 */
export function formatQuantity(value: number): string {
  return new Intl.NumberFormat('es-PE', { maximumFractionDigits: 2 }).format(value)
}

/**
 * Formatea una fecha ISO (UTC) a dd/mm/aaaa, hh:mm en zona horaria de Lima. Es
 * `formatDate` con hora: el kardex necesita distinguir varios movimientos del
 * mismo día, donde solo la fecha los volvería indistinguibles.
 */
export function formatDateTime(isoDate: string): string {
  return new Intl.DateTimeFormat('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'America/Lima',
  }).format(new Date(isoDate))
}
