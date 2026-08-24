/** Zona de toda la operación de la empresa. Perú no tiene horario de verano, pero se
 * ancla al nombre de la zona y nunca a un `-05:00` literal: el país sí tuvo cambios de
 * hora en 1990 y 1994, y una zona con nombre sobrevive a que vuelva a pasar. */
const LIMA_TIME_ZONE = 'America/Lima'

/** `en-CA` formatea la fecha como `YYYY-MM-DD`, que es justo lo que el contrato pide. */
const LIMA_DATE_FORMATTER = new Intl.DateTimeFormat('en-CA', {
  timeZone: LIMA_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/**
 * El día de hoy en Lima, como `YYYY-MM-DD`.
 *
 * La zona del navegador no participa: quien carga un servicio desde otro país está
 * registrando un viaje peruano, así que "hoy" es el día que es en Perú. Con el día
 * local del navegador, entre las 19:00 de Lima y la medianoche el resto de América
 * ya está en la fecha siguiente y el formulario propondría un día que en la oficina
 * todavía no llegó.
 */
export function todayInLima(): string {
  return LIMA_DATE_FORMATTER.format(new Date())
}

/**
 * `true` si la fecha (`YYYY-MM-DD`) quedó atrás respecto de hoy en Lima.
 *
 * Compara los textos y no dos `Date`: dos fechas ya normalizadas a la misma zona se
 * ordenan igual como cadenas, mientras que `new Date('2026-08-24')` reintroduciría
 * por la ventana la zona del navegador que este módulo existe para dejar afuera.
 */
export function isPastInLima(isoDate: string): boolean {
  return isoDate < todayInLima()
}
