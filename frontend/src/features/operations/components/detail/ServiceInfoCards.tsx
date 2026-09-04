import type { ServiceDetailResponse } from '../../../../api'
import {
  formatCurrency,
  formatDateOnly,
  formatDateTime,
  formatQuantity,
} from '../../../../shared/utils/formatters'
import { TRIP_SCOPE_LABELS } from '../../status/serviceStatusPresentation'
import { DetailCard, Field } from './DetailCard'

interface ServiceInfoCardsProps {
  service: ServiceDetailResponse
  /** `false` para el despacho: la tarjeta del precio no se arma. */
  showPrice: boolean
}

/**
 * Las medidas de la carga como una sola línea, o un guion si no se cargó ninguna.
 *
 * Son opcionales de a una: un viaje puede tener el largo y no el ancho. Se muestran
 * las que hay en vez de exigir las tres o descartar todo, porque un dato parcial
 * sigue siendo el dato que alguien cargó. De los 905 viajes migrados, 171 llegan
 * con alguna medida faltante y 145 sin ninguna, así que el caso incompleto es
 * corriente y no un borde.
 *
 * Los números pasan por el formateador de la casa: son magnitudes de es-PE, y una
 * carga de más de mil metros no existe pero el formateador es el mismo que el del
 * peso, donde los miles sí aparecen todo el tiempo.
 */
function formatDimensions(service: ServiceDetailResponse): string {
  const parts = [
    service.lengthM == null ? null : `Largo ${formatQuantity(service.lengthM)} m`,
    service.widthM == null ? null : `Ancho ${formatQuantity(service.widthM)} m`,
    service.heightM == null ? null : `Alto ${formatQuantity(service.heightM)} m`,
  ].filter((part): part is string => part !== null)
  return parts.length === 0 ? '—' : parts.join(' · ')
}

/**
 * Las fichas de datos del viaje. El código, el cliente y el estado ya viven en el
 * encabezado de la pantalla, y el rastro de quién lo tocó vive en la bitácora.
 *
 * El precio va en su PROPIA tarjeta, no como un campo más, para que sacárselo al
 * despacho no deje un hueco en medio de una grilla: RN-OP8 dice que el servidor le
 * OMITE `price` y `currencyCode` (ausentes, no null), así que para ese rol la
 * tarjeta directamente no existe. La garantía es del servidor; acá se evita
 * dibujar una ficha con guiones donde otros ven importes.
 */
export function ServiceInfoCards({ service, showPrice }: ServiceInfoCardsProps) {
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <DetailCard title="Viaje" headingId="service-trip-heading">
          <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Origen" value={service.origin} />
            <Field label="Destino" value={service.destination} />
            <Field label="Ámbito" value={TRIP_SCOPE_LABELS[service.tripScope]} />
          </dl>
        </DetailCard>

        <DetailCard title="Carga" headingId="service-cargo-heading">
          <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Tipo de carga" value={service.cargoType.name} />
            <Field label="Peso" value={`${formatQuantity(service.weightKg)} kg`} />
            {/* Las medidas ocupan la fila entera: son tres valores con su unidad
                separados por puntos, y en media celda el salto cae en cualquier
                lado, incluso entre un número y su "m". */}
            <Field
              label="Medidas"
              value={formatDimensions(service)}
              className="sm:col-span-2"
            />
          </dl>
        </DetailCard>

        {showPrice && (
          <DetailCard title="Precio" headingId="service-price-heading">
            <dl className="mt-3">
              <Field
                label="Acordado"
                value={
                  service.price == null || !service.currencyCode
                    ? '—'
                    : formatCurrency(service.price, service.currencyCode)
                }
              />
            </dl>
          </DetailCard>
        )}

        {/* Las tres fechas del viaje juntas: la que se prometió y las dos que
            ocurrieron. Separadas obligaban a mirar dos fichas para saber si el
            viaje salió cuando se dijo que iba a salir, que es la pregunta que
            alguien le hace a esta pantalla. */}
        <DetailCard title="Fechas" headingId="service-dates-heading">
          <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            {/* La tentativa es una fecha sin hora, así que va por el formateador
                que no aplica zona: por los otros se leería como medianoche UTC y
                en Lima mostraría el día anterior. */}
            <Field
              label="Fecha tentativa"
              value={formatDateOnly(service.tentativeDate)}
              className="sm:col-span-2"
            />
            {/* Inicio y fin están vacíos mientras el viaje no arranque o no cierre:
                los fija la transición de estado. El guion es la respuesta correcta,
                no una falla.

                Con hora y no solo el día: para operaciones, un viaje que arrancó a
                las 06:00 y uno que arrancó a las 21:30 no son lo mismo. */}
            <Field
              label="Inicio real"
              value={service.startDateTime ? formatDateTime(service.startDateTime) : '—'}
            />
            <Field
              label="Fin real"
              value={service.endDateTime ? formatDateTime(service.endDateTime) : '—'}
            />
          </dl>
        </DetailCard>
      </div>

      {/* Sección propia y a ancho completo, siguiendo a almacén: se cargan en un
          campo de varias líneas, así que se muestran respetando los saltos. En una
          celda a media grilla quedarían colapsadas en un bloque corrido. No se
          dibuja si no hay nada, porque la mayoría de los viajes heredados llegan
          sin observaciones y una sección vacía no dice nada que el silencio no
          diga mejor.

          El `trim` es el mismo criterio que la bitácora, y por el mismo motivo:
          cinco de los viajes migrados traen espacio en blanco en los bordes (uno
          de ellos un salto de línea, que respetado al pie de la letra dibuja una
          línea vacía). Recortado también decide si la sección se dibuja, así que
          unas observaciones que solo tengan espacios cuentan como no tenerlas. */}
      {service.observations?.trim() && (
        <DetailCard title="Observaciones" headingId="service-observations-heading">
          <p className="mt-2 whitespace-pre-line break-words text-sm text-fg-body">
            {service.observations.trim()}
          </p>
        </DetailCard>
      )}
    </div>
  )
}
