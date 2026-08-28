import { z } from 'zod'
import { NO_CONTROL } from '../../../shared/utils/sanitizeText'

/*
 * Las piezas de campo que comparten el ALTA y la EDICIÓN de un viaje.
 *
 * La frontera no es simétrica, y conviene tenerla escrita porque leerla al revés cuesta
 * una duplicación. El alta manda tres campos que la edición no: el cliente, el ámbito y
 * el tipo de carga, inmutables después del alta. La edición manda tres que el alta no
 * tiene: las dos fechas reales, que ahí se CORRIGEN y no se fijan, y la justificación
 * obligatoria. Esos seis viven cada uno en el schema de su formulario.
 *
 * Lo que queda en el medio son los diez campos del viaje que los dos mandan igual, y es
 * lo que vive acá: sus topes de columna y la regla de cada uno, escrita una sola vez.
 *
 * Vive acá y no en `shared/`: son los topes y las reglas de ESTE recurso, y quien los
 * cambie tiene que ver los dos formularios a la vez.
 */

/** Topes de texto del contrato para el origen y el destino. */
export const SERVICE_PLACE_MAX_LENGTH = 255

/** Tope de las observaciones, espejo del `maxLength` del contrato. */
export const SERVICE_OBSERVATIONS_MAX_LENGTH = 500

/**
 * Topes de los importes, espejo de lo que el backend exige con `@Digits`: ocho cifras
 * enteras en el peso y las medidas, diez en el precio, y dos decimales en todos. Sin
 * esto el formulario deja salir un valor que vuelve como 400.
 */
export const MEASURE_MAX = 99999999.99
export const PRICE_MAX = 9999999999.99

/** Ventana de fechas que la columna admite; el contrato responde 400 fuera de ella. */
export const SERVICE_DATE_MIN = '1900-01-01'
export const SERVICE_DATE_MAX = '2999-12-31'

/**
 * Origen y destino son de UNA línea: el servidor los rechaza con cualquier carácter
 * de control, saltos incluidos, porque los escribe en su log y un salto inventaría
 * una línea entera. Se valida acá para explicarlo en el campo y no con un 400 sobre
 * el formulario entero.
 *
 * NO reusa el `NO_CONTROL` compartido a propósito: aquel permite tabulaciones y
 * saltos, que en un texto libre son legítimos y acá no. Es una regla más estricta,
 * no la misma escrita dos veces.
 *
 * Lo que este patrón NO cubre, y el servidor sí: los controles C1 (`U+0080` a `U+009F`),
 * que `Character.isISOControl` rechaza y acá pasan. Un origen pegado con esos bytes sale
 * del formulario y vuelve como un 400 sobre el formulario entero, que es justamente lo
 * que esta regla existe para evitar. Encolado aparte: cerrarlo cambia lo que el alta
 * acepta, y esto es la mudanza.
 */
// Los controles del patrón son intencionales (espejan PARTE de lo que rechaza el
// backend; ver el hueco de los C1 arriba).
// Los dos separadores de línea de Unicode van incluidos porque el servidor también los
// rechaza, y son literalmente lo que esta regla dice prohibir.
// eslint-disable-next-line no-control-regex
const SINGLE_LINE = /^[^\x00-\x1F\x7F\u2028\u2029]*$/

export const placeSchema = (fieldLabel: string) =>
  z
    .string()
    .trim()
    .min(1, `Indica el ${fieldLabel}`)
    .max(SERVICE_PLACE_MAX_LENGTH, `Máximo ${SERVICE_PLACE_MAX_LENGTH} caracteres`)
    .regex(SINGLE_LINE, `El ${fieldLabel} va en una sola línea, sin saltos`)

/**
 * Los campos numéricos viajan por el formulario como TEXTO y se convierten acá.
 *
 * No es una vuelta de más. Registrado como número, el campo lleva una conversión de
 * `''` a vacío que react-hook-form aplica también al valor por omisión, tal cual y
 * sin que el usuario toque nada: con un valor por omisión que no es un string, esa
 * guarda no lo atrapa y `Number()` lo vuelve cero. El efecto era que llenar el largo
 * hacía aparecer "el ancho debe ser mayor a 0" sobre dos campos que nadie había
 * tocado. Con texto no hay conversión que reciba un valor de otro tipo: vacío es
 * vacío y el schema decide qué significa en cada caso.
 */
/** Hasta dos decimales, que es lo que la columna guarda. */
function hasAtMostTwoDecimals(value: string): boolean {
  const [, decimals = ''] = value.split('.')
  return decimals.length <= 2
}

export const requiredAmountSchema = (
  missingMessage: string,
  positiveMessage: string,
  max: number,
) =>
  z
    .string()
    .trim()
    .min(1, missingMessage)
    .refine((value) => Number.isFinite(Number(value)), { message: 'Tiene que ser un número' })
    .refine((value) => Number(value) > 0, { message: positiveMessage })
    .refine(hasAtMostTwoDecimals, { message: 'Como máximo 2 decimales' })
    .refine((value) => Number(value) <= max, { message: 'El valor es demasiado grande' })
    .transform(Number)

/**
 * Una medida opcional del viaje (largo, ancho, alto). Vacío es válido y significa
 * ausente: el contrato las tipa nullable. Cuando trae un número, tiene que ser
 * mayor que cero, igual que un importe obligatorio.
 */
export const optionalMeasureSchema = (fieldLabel: string) =>
  z
    .string()
    .trim()
    .refine((value) => value === '' || Number.isFinite(Number(value)), {
      message: `El ${fieldLabel} tiene que ser un número`,
    })
    .refine((value) => value === '' || Number(value) > 0, {
      message: `El ${fieldLabel} debe ser mayor a 0`,
    })
    .refine((value) => value === '' || hasAtMostTwoDecimals(value), {
      message: `El ${fieldLabel} admite como máximo 2 decimales`,
    })
    .refine((value) => value === '' || Number(value) <= MEASURE_MAX, {
      message: `El ${fieldLabel} es demasiado grande`,
    })
    .transform((value) => (value === '' ? null : Number(value)))

/**
 * La fecha tentativa del viaje.
 *
 * Puede ser pasada: el contrato admite el registro retroactivo, y el viaje que salió
 * ayer se carga hoy. La ventana que sí se exige es la de la columna.
 */
export const tentativeDateSchema = () =>
  z
    .string()
    .min(1, 'Indica la fecha tentativa')
    .refine((value) => value >= SERVICE_DATE_MIN && value <= SERVICE_DATE_MAX, {
      message: `La fecha debe estar entre ${SERVICE_DATE_MIN} y ${SERVICE_DATE_MAX}`,
    })

/**
 * La moneda del importe, elegida de un catálogo.
 *
 * El schema solo exige que haya una elegida: si existe y sigue activa lo contesta el
 * servidor, que es el único que tiene el catálogo a la vista.
 */
export const currencySchema = () =>
  z
    .number({ message: 'Elige la moneda del servicio' })
    .int()
    .positive('Elige la moneda del servicio')

/**
 * Las observaciones del viaje: texto libre y opcional.
 *
 * Es el único campo del viaje donde los saltos y las tabulaciones son legítimos, así
 * que usa el `NO_CONTROL` compartido y no el patrón de una línea. Vive acá y no escrito
 * en cada formulario porque ese `regex` es la única guarda del byte NUL que el contrato
 * exige rechazar, y es la clase de línea que al copiarse a mano se omite.
 */
export const observationsSchema = () =>
  z
    .string()
    .trim()
    .max(SERVICE_OBSERVATIONS_MAX_LENGTH, `Máximo ${SERVICE_OBSERVATIONS_MAX_LENGTH} caracteres`)
    .regex(NO_CONTROL, 'No se permiten caracteres de control.')
    .optional()
