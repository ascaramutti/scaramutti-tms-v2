import { z } from 'zod'
import type { ServiceCreateRequest, TripScope } from '../../../api'
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
 * Ámbitos del viaje. Dominio cerrado sin catálogo administrable: el contrato lo
 * declara como enum y no hay endpoint que lo liste, así que el selector sale de acá.
 */
export const TRIP_SCOPE_OPTIONS: readonly { value: TripScope; label: string }[] = [
  { value: 'LOCAL', label: 'Local' },
  { value: 'PROVINCIA', label: 'Provincia' },
]

/*
 * Se reexporta la superficie que este archivo ya publicaba, la consuma alguien o no: la
 * mudanza no le cambia los imports a nadie, y tampoco le saca a nadie un export que
 * estaba. Que dos de los seis hoy no tengan consumidor viene de antes de la mudanza.
 */
export {
  MEASURE_MAX,
  PRICE_MAX,
  SERVICE_DATE_MAX,
  SERVICE_DATE_MIN,
  SERVICE_OBSERVATIONS_MAX_LENGTH,
  SERVICE_PLACE_MAX_LENGTH,
} from './service-fields.schema'

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
  tentativeDate: tentativeDateSchema(),
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
  currencyId: currencySchema(),
  observations: observationsSchema(),
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
