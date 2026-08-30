/** Zona de toda la operación de la empresa. Perú no tiene horario de verano, pero se
 * ancla al nombre de la zona y nunca a un `-05:00` literal: el país sí los tuvo, y el
 * de 1994 lo fija un caso de esta suite. Una zona con nombre sobrevive a que vuelva a
 * pasar. */
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

/**
 * El mismo instante, pero con la hora puesta, y en el formato exacto que come un
 * `<input type="datetime-local">`: `YYYY-MM-DDTHH:mm`.
 *
 * Se pide `hourCycle: 'h23'` en vez de `hour12: false`, y la diferencia está en quién
 * decide cómo se escribe la medianoche. `hour12: false` pide reloj de 24 horas y deja
 * librado al locale si eso es `00` o `24`; `h23` lo fija. La distinción importa porque
 * `…T24:00` no es un valor que el input acepte (el campo queda vacío) y además ordena
 * mal como texto, que es como este módulo compara.
 *
 * Con el motor de este repo las dos formas dan `00`, así que ningún test las distingue:
 * se midió y la sustitución sobrevive. Queda escrito así igual, porque lo que se elige
 * acá no es un valor sino dejar de depender de a qué ciclo mapee cada locale.
 */
const LIMA_DATE_TIME_FORMATTER = new Intl.DateTimeFormat('en-CA', {
  timeZone: LIMA_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** Largo de `YYYY-MM-DDTHH:mm`, que es la precisión con la que este módulo compara. */
const WALL_CLOCK_LENGTH = 16

/**
 * Un instante, leído como el reloj de pared que marca en Lima.
 *
 * Se arma con `formatToParts` en vez de con `format` porque el texto que devuelve el
 * formateador lleva una coma entre la fecha y la hora, y lo que el input necesita es
 * una `T`. Armarlo por partes también deja el resultado a salvo de que el separador
 * cambie entre motores.
 */
export function formatLimaWallClock(instant: Date): string {
  const parts: Record<string, string> = {}
  for (const part of LIMA_DATE_TIME_FORMATTER.formatToParts(instant)) {
    parts[part.type] = part.value
  }
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}

/**
 * Un reloj de pared leído como si sus números fueran UTC, en milisegundos.
 *
 * No es el instante que el texto representa: es una regla para medir la distancia
 * entre dos relojes de pared sin que la zona del navegador entre en la cuenta.
 */
function wallClockAsUtcMillis(wallClock: string): number {
  return Date.parse(`${wallClock.slice(0, WALL_CLOCK_LENGTH)}:00Z`)
}

/**
 * El ahora, como reloj de pared de Lima, listo para precargar un `datetime-local`.
 *
 * La zona del navegador no participa, por lo mismo que en `todayInLima`: quien registra
 * un viaje desde otro país está anotando la hora a la que salió el camión en Perú.
 */
export function nowInLimaForInput(): string {
  return formatLimaWallClock(new Date())
}

/**
 * `true` si ese reloj de pared todavía no llegó en Lima.
 *
 * El instante exacto NO es futuro, y esa no es una sutileza: el campo viene precargado
 * con `nowInLimaForInput()`, así que con el borde al revés el formulario nacería
 * inválido con su propio valor por defecto y nadie podría iniciar un viaje sin tocar
 * la fecha.
 *
 * Compara los textos, igual que `isPastInLima`, y recorta los dos lados al minuto: hay
 * navegadores que devuelven el valor del input con segundos, y `"…T14:00:30"` es mayor
 * como cadena que `"…T14:00"` aunque sea el mismo minuto que el usuario eligió.
 */
export const FUTURE_DATE_MESSAGE = 'La fecha no puede estar en el futuro'

export function isFutureInLima(wallClock: string): boolean {
  return wallClock.slice(0, WALL_CLOCK_LENGTH) > nowInLimaForInput()
}

/**
 * Un reloj de pared de Lima al instante UTC que le corresponde, en ISO.
 *
 * Es la conversión que el resto del módulo existe para proteger, y la que NO se puede
 * escribir como `new Date(texto).toISOString()`: el input entrega texto sin zona, y ese
 * constructor lo interpreta en la del navegador. Quien cargue "21:30" desde fuera de
 * Perú guardaría las 21:30 suyas.
 *
 * El desplazamiento no se escribe como `-05:00` sino que se DERIVA del mismo formateador
 * anclado a la zona con nombre, por el motivo que ya explica el encabezado del módulo:
 * el literal es cierto hoy y no es una garantía. El método es medir cuánto se corre el
 * reloj limeño respecto de un instante tentativo y corregir por esa diferencia.
 *
 * La segunda pasada existe para el caso en que la corrección aterrice del otro lado de
 * un cambio de hora, donde el desplazamiento medido en el primer intento ya no es el que
 * rige en el instante corregido. Perú tuvo esos saltos en 1990 y 1994, y por eso el
 * módulo ancla al nombre de la zona en vez de a un desfase fijo.
 *
 * Lo que devuelve es siempre un instante REAL. En la hora que un adelanto de relojes se
 * saltea —esa hora de pared no existió— no hay instante que le corresponda, y ahí elige
 * el de antes del salto: `1994-01-01T00:00` sale como las 23:00 del 31 de diciembre. No
 * es un caso alcanzable registrando un viaje de hoy; queda escrito para que nadie lo lea
 * como que la ida y la vuelta coinciden siempre.
 */
export function limaInputToIsoInstant(wallClock: string): string {
  const target = wallClockAsUtcMillis(wallClock)
  let instant = target
  for (let pass = 0; pass < 2; pass += 1) {
    const drift = wallClockAsUtcMillis(formatLimaWallClock(new Date(instant))) - instant
    instant = target - drift
  }
  return new Date(instant).toISOString()
}
