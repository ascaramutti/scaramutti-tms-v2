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
 * Formatea una fecha ISO (UTC) a formato corto dd/mm/aaaa en la zona del
 * negocio. El backend interpreta las fechas en America/Lima; se fija el
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
 * interpretaría un date-only como UTC medianoche y en America/Lima retrocedería
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
 * Formatea una cantidad de inventario en formato es-PE (separador de miles).
 * El contrato tipa stock y mínimos como `number`, así que admite decimales
 * (unidades como litros o galones): se muestran hasta 2, sin rellenar con
 * ceros las cantidades enteras ("1,250" y no "1,250.00").
 */
export function formatQuantity(value: number): string {
  return new Intl.NumberFormat('es-PE', { maximumFractionDigits: 2 }).format(value)
}

/**
 * Formatea una fecha ISO (UTC) a dd/mm/aaaa, hh:mm en la zona del negocio. Es
 * `formatDate` con hora: el kardex necesita distinguir varios movimientos del
 * mismo día, donde solo la fecha los volvería indistinguibles.
 *
 * Pide `hourCycle: 'h23'` y no `hour12: false` por lo mismo que `formatLimaWallClock`
 * en `limaDate.ts`: `hour12: false` pide reloj de 24 horas y deja al locale decidir si la
 * medianoche es `00` o `24`; `h23` lo fija. Con este motor las dos formas dan
 * `00`, así que ningún test las distingue y la sustitución sobrevive: lo que se
 * elige acá no es un valor, es dejar de depender del ciclo horario del locale.
 */
export function formatDateTime(isoDate: string): string {
  return new Intl.DateTimeFormat('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: 'America/Lima',
  }).format(new Date(isoDate))
}
