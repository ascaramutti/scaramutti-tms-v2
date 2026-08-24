import { z } from 'zod'
import type { ServiceCreateRequest, TripScope } from '../../../api'
import { NO_CONTROL } from '../../../shared/utils/sanitizeText'

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
 * Ámbitos del viaje. Dominio cerrado sin catálogo administrable: el contrato lo
 * declara como enum y no hay endpoint que lo liste, así que el selector sale de acá.
 */
export const TRIP_SCOPE_OPTIONS: readonly { value: TripScope; label: string }[] = [
  { value: 'LOCAL', label: 'Local' },
  { value: 'PROVINCIA', label: 'Provincia' },
]

/**
 * Origen y destino son de UNA línea: el servidor los rechaza con cualquier carácter
 * de control, saltos incluidos, porque los escribe en su log y un salto inventaría
 * una línea entera. Se valida acá para explicarlo en el campo y no con un 400 sobre
 * el formulario entero.
 *
 * NO reusa el `NO_CONTROL` compartido a propósito: aquel permite tabulaciones y
 * saltos, que en un texto libre son legítimos y acá no. Es una regla más estricta,
 * no la misma escrita dos veces.
 */
// Los controles del patrón son intencionales (espejan lo que rechaza el backend).
// Los dos separadores de línea de Unicode van incluidos porque el servidor también los
// rechaza, y son literalmente lo que esta regla dice prohibir.
// eslint-disable-next-line no-control-regex
const SINGLE_LINE = /^[^\x00-\x1F\x7F\u2028\u2029]*$/

const placeSchema = (fieldLabel: string) =>
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

const requiredAmountSchema = (
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
 * mayor que cero, igual que el peso.
 */
const optionalMeasureSchema = (fieldLabel: string) =>
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
 * Alta de un servicio. Espeja `ServiceCreateRequest`.
 *
 * Lo que este schema NO intenta decidir: si el cliente, el tipo de carga o la
 * moneda existen y están activos (el servidor responde 400 con el detalle), y si el
 * alta repite un viaje cargado hace segundos (409 `OPS-007`, que necesita ver el
 * historial del usuario). Son datos que el formulario no tiene.
 *
 * La fecha tentativa PUEDE ser pasada: el contrato admite el registro retroactivo
 * ("el viaje salió ayer y recién hoy se carga"). La pantalla avisa, no bloquea.
 */
export const serviceCreateFormSchema = z.object({
  clientId: z
    .number({ message: 'Selecciona el cliente' })
    .int()
    .positive('Selecciona el cliente'),
  tripScope: z.enum(['LOCAL', 'PROVINCIA'], { message: 'Elige el ámbito del viaje' }),
  tentativeDate: z
    .string()
    .min(1, 'Indica la fecha tentativa')
    .refine((value) => value >= SERVICE_DATE_MIN && value <= SERVICE_DATE_MAX, {
      message: `La fecha debe estar entre ${SERVICE_DATE_MIN} y ${SERVICE_DATE_MAX}`,
    }),
  origin: placeSchema('origen'),
  destination: placeSchema('destino'),
  cargoTypeId: z
    .number({ message: 'Selecciona el tipo de carga' })
    .int()
    .positive('Selecciona el tipo de carga'),
  weightKg: requiredAmountSchema('Indica el peso', 'El peso debe ser mayor a 0', MEASURE_MAX),
  lengthM: optionalMeasureSchema('largo'),
  widthM: optionalMeasureSchema('ancho'),
  heightM: optionalMeasureSchema('alto'),
  price: requiredAmountSchema('Indica el precio', 'El precio debe ser mayor a 0', PRICE_MAX),
  currencyId: z
    .number({ message: 'Elige la moneda del servicio' })
    .int()
    .positive('Elige la moneda del servicio'),
  observations: z
    .string()
    .trim()
    .max(SERVICE_OBSERVATIONS_MAX_LENGTH, `Máximo ${SERVICE_OBSERVATIONS_MAX_LENGTH} caracteres`)
    .regex(NO_CONTROL, 'No se permiten caracteres de control.')
    .optional(),
})

/** Lo que el formulario guarda mientras se escribe (los importes, como texto). */
export type ServiceCreateFormInput = z.input<typeof serviceCreateFormSchema>

/** Lo ya validado y convertido, que es lo que se manda. */
export type ServiceCreateFormValues = z.output<typeof serviceCreateFormSchema>

/**
 * Traduce el formulario al cuerpo del POST.
 *
 * Las medidas y las observaciones viajan como `null` cuando quedaron vacías, y no
 * ausentes: el contrato las tipa nullable y mandarlas explícitas deja el cuerpo
 * completo, sin que el servidor tenga que distinguir "no lo sé" de "no tiene".
 */
export function toServiceCreateRequest(values: ServiceCreateFormValues): ServiceCreateRequest {
  return {
    clientId: values.clientId,
    tripScope: values.tripScope,
    tentativeDate: values.tentativeDate,
    origin: values.origin,
    destination: values.destination,
    cargoTypeId: values.cargoTypeId,
    weightKg: values.weightKg,
    lengthM: values.lengthM,
    widthM: values.widthM,
    heightM: values.heightM,
    price: values.price,
    currencyId: values.currencyId,
    observations: values.observations?.trim() ? values.observations.trim() : null,
  }
}
