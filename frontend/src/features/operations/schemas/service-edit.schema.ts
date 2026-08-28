import { z } from 'zod'
import type { CurrencyResponse, ServiceDetailResponse, ServiceUpdateRequest } from '../../../api'
import { formatLimaWallClock, limaInputToIsoInstant } from '../utils/limaDate'
import {
  MEASURE_MAX,
  PRICE_MAX,
  currencySchema,
  observationsSchema,
  optionalMeasureSchema,
  placeSchema,
  requiredAmountSchema,
  tentativeDateSchema,
} from './service-fields.schema'

/**
 * Tope de la justificación, espejo del `maxLength` del contrato.
 */
export const JUSTIFICATION_MAX_LENGTH = 500

/**
 * Mínimo de la justificación, medido sobre el texto YA RECORTADO.
 *
 * El contrato declara `minLength: 10` y además un patrón, y no es redundante: el
 * `minLength` cuenta el texto crudo, así que sin el patrón un cliente generado aceptaría
 * diez espacios y el servidor los rechazaría. Acá se mide directamente sobre el texto
 * recortado, que es la misma regla dicha de la forma en que este formulario la puede
 * aplicar.
 */
export const JUSTIFICATION_MIN_LENGTH = 10

/**
 * La corrección de una fecha real (el inicio o el fin del viaje).
 *
 * Vacío significa SIN CAMBIO, no borrar: una fecha real no se borra, y el contrato trata
 * el campo ausente y el nulo como lo mismo. El campo solo se ofrece cuando el viaje ya
 * tiene esa fecha, así que el vacío acá es "no la toqué".
 *
 * Viaja como reloj de pared de Lima y se convierte al instante recién al mandar, igual
 * que en las transiciones de estado: quien corrige desde otro país está escribiendo la
 * hora que marcaba el reloj en Perú.
 */
const realDateTimeSchema = () => z.string().trim()

/**
 * Edición de un viaje. Espeja `ServiceUpdateRequest`.
 *
 * Casi todos los campos salen de `service-fields.schema`, que es donde viven las reglas
 * que este formulario comparte con el alta. Lo propio de la edición son tres: las dos
 * fechas reales, que acá se CORRIGEN y no se fijan, y la justificación obligatoria.
 *
 * Lo que este schema NO intenta decidir, porque son datos que el formulario no tiene:
 * si la moneda sigue activa (solo se exige al cambiarla, y lo sabe el servidor), si el
 * viaje sigue siendo editable (un cancelado o eliminado responde 409) y si la versión
 * que se está editando sigue siendo la última (el `If-Match` y su 412).
 */
export const serviceEditFormSchema = z
  .object({
    tentativeDate: tentativeDateSchema(),
    origin: placeSchema('origen'),
    destination: placeSchema('destino'),
    weightKg: requiredAmountSchema('Indica el peso', 'El peso debe ser mayor a 0', MEASURE_MAX),
    lengthM: optionalMeasureSchema('largo'),
    widthM: optionalMeasureSchema('ancho'),
    heightM: optionalMeasureSchema('alto'),
    price: requiredAmountSchema('Indica el precio', 'El precio debe ser mayor a 0', PRICE_MAX),
    currencyId: currencySchema(),
    observations: observationsSchema(),
    startDateTime: realDateTimeSchema(),
    endDateTime: realDateTimeSchema(),
    justification: z
      .string()
      .trim()
      .min(JUSTIFICATION_MIN_LENGTH, `Explica el cambio en al menos ${JUSTIFICATION_MIN_LENGTH} caracteres`)
      .max(JUSTIFICATION_MAX_LENGTH, `Máximo ${JUSTIFICATION_MAX_LENGTH} caracteres`),
  })
  .refine(
    (values) =>
      values.startDateTime === '' ||
      values.endDateTime === '' ||
      values.endDateTime >= values.startDateTime,
    {
      // El servidor también lo valida; acá se explica sobre el campo que hay que mover
      // en vez de devolver un 400 sobre el formulario entero. Se comparan los dos
      // relojes de pared como texto, que es comparable por su formato.
      path: ['endDateTime'],
      message: 'El fin no puede ser anterior al inicio',
    },
  )

/** Lo que el formulario guarda mientras se escribe (los importes, como texto). */
export type ServiceEditFormInput = z.input<typeof serviceEditFormSchema>

/** Lo ya validado y convertido, que es lo que se manda. */
export type ServiceEditFormValues = z.output<typeof serviceEditFormSchema>

/**
 * Los valores con los que el formulario abre, tomados del viaje que se está editando.
 *
 * Los importes se cargan como texto porque el formulario los maneja así de punta a
 * punta, y las fechas reales como reloj de pared de Lima. El precio puede no venir: al
 * despacho el servidor se lo omite, y ese usuario no llega a esta pantalla (el mismo
 * cuerpo que exige el precio es lo que le devuelve un 403), pero el tipo lo admite y el
 * valor por omisión no puede ser un `undefined` que react-hook-form convierta en cero.
 *
 * Necesita el catálogo porque el detalle publica la moneda por su CÓDIGO y el cuerpo del
 * PUT pide su id, que la respuesta no trae por ningún lado.
 */
export function toServiceEditFormValues(
  service: ServiceDetailResponse,
  currencies: readonly CurrencyResponse[],
): ServiceEditFormInput {
  return {
    tentativeDate: service.tentativeDate,
    origin: service.origin,
    destination: service.destination,
    weightKg: String(service.weightKg),
    lengthM: service.lengthM === null ? '' : String(service.lengthM),
    widthM: service.widthM === null ? '' : String(service.widthM),
    heightM: service.heightM === null ? '' : String(service.heightM),
    price: service.price === undefined ? '' : String(service.price),
    currencyId: resolveCurrencyId(service.currencyCode, currencies),
    observations: service.observations ?? '',
    startDateTime: toWallClockOrEmpty(service.startDateTime),
    endDateTime: toWallClockOrEmpty(service.endDateTime),
    justification: '',
  }
}

/**
 * El id de la moneda del viaje, buscado por su código en el catálogo.
 *
 * El detalle publica `currencyCode` y nunca el id, así que esta traducción es inevitable
 * mientras el contrato sea el que es (encolado: que el detalle exponga la moneda como
 * objeto con id, igual que cotizaciones).
 *
 * REVIENTA si el código no está en el catálogo, en vez de caer a un id vacío: el
 * formulario abriría sin moneda seleccionada y el usuario guardaría con otra sin notar
 * que la cambió. Es el mismo criterio con que la flota mapea su estado por nombre. Que
 * el catálogo llegue completo, dadas de baja incluidas, es lo que hace que este camino
 * no se dispare por un viaje viejo perfectamente válido.
 */
function resolveCurrencyId(
  code: string | undefined,
  currencies: readonly CurrencyResponse[],
): number {
  const found = currencies.find((currency) => currency.code === code)
  if (!found) {
    throw new Error(`La moneda ${code ?? '(ausente)'} del viaje no está en el catálogo`)
  }
  return found.id
}

/** Un instante del servidor, como reloj de pared de Lima; vacío si el viaje no lo tiene. */
function toWallClockOrEmpty(instant: string | null | undefined): string {
  return instant ? formatLimaWallClock(new Date(instant)) : ''
}

/**
 * Traduce el formulario al cuerpo del PUT.
 *
 * Las medidas y las observaciones viajan como `null` cuando quedaron vacías, y eso las
 * VACÍA: el contrato dice que un cuerpo parcial borra lo que no incluye, así que
 * mandarlas explícitas es lo que hace que el formulario signifique lo que muestra.
 *
 * Las dos fechas reales son la excepción y van al revés: vacías se OMITEN, porque ahí
 * ausente significa sin cambio. Es la misma diferencia que el contrato marca, y la razón
 * es que una fecha real no se borra.
 */
export function toServiceUpdateRequest(values: ServiceEditFormValues): ServiceUpdateRequest {
  return {
    tentativeDate: values.tentativeDate,
    origin: values.origin,
    destination: values.destination,
    weightKg: values.weightKg,
    lengthM: values.lengthM,
    widthM: values.widthM,
    heightM: values.heightM,
    price: values.price,
    currencyId: values.currencyId,
    observations: values.observations?.trim() ? values.observations.trim() : null,
    ...(values.startDateTime === ''
      ? {}
      : { startDateTime: limaInputToIsoInstant(values.startDateTime) }),
    ...(values.endDateTime === '' ? {} : { endDateTime: limaInputToIsoInstant(values.endDateTime) }),
    justification: values.justification,
  }
}
