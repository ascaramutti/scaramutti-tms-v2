import type { ServiceDetailResponse } from '../../../../api'
import { formatDateTime } from '../../../../shared/utils/formatters'
import { DetailCard, Field } from './DetailCard'

interface ServiceResourcesProps {
  service: ServiceDetailResponse
}

/**
 * Recursos del viaje: los principales (conductor, tracto, carreta) y los refuerzos
 * sumados en ruta.
 *
 * Los tres principales son null mientras el viaje esté pendiente de asignación, y
 * la carreta puede seguir en null después porque es opcional: hay carga que no la
 * lleva. El guion dice eso, no que falte un dato.
 *
 * Los refuerzos se muestran y no se administran: sumarlos y darlos de baja son
 * otros dos endpoints, que llegan en su propio cambio. Una fila de refuerzo es un
 * PEDIDO y no un recurso suelto: puede traer los tres a la vez, uno solo o
 * cualquier combinación, y por eso se lista completa en vez de una línea por
 * recurso.
 */
export function ServiceResources({ service }: ServiceResourcesProps) {
  return (
    <div className="space-y-4">
      <DetailCard title="Recursos asignados" headingId="service-resources-heading">
        <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Field label="Conductor" value={service.driver?.fullName ?? '—'} />
          <Field label="Tracto" value={service.tractor?.plate ?? '—'} />
          <Field label="Carreta" value={service.trailer?.plate ?? '—'} />
        </dl>
      </DetailCard>

      <DetailCard title="Refuerzos" headingId="service-additional-heading">
        {service.additionalResources.length === 0 ? (
          // Es el caso NORMAL, no un borde: ninguno de los 905 viajes migrados
          // tiene refuerzos, así que esto es lo que se va a ver casi siempre.
          <p className="mt-2 text-sm text-slate-500">
            Este viaje no tiene recursos de refuerzo.
          </p>
        ) : (
          <ul className="mt-3 space-y-3">
            {service.additionalResources.map((resource) => (
              <li key={resource.id} className="border-l-2 border-slate-200 pl-4">
                {/* Las dos PLACAS van rotuladas: seguidas y sin rótulo no dicen
                    cuál es el tracto y cuál la carreta, y una sola se lee como
                    tracto aunque sea la carreta. El conductor no lo necesita: un
                    nombre no se confunde con una placa. */}
                <p className="text-sm text-slate-900">
                  {[
                    resource.driver && resource.driver.fullName,
                    resource.tractor && `Tracto ${resource.tractor.plate}`,
                    resource.trailer && `Carreta ${resource.trailer.plate}`,
                  ]
                    .filter((value): value is string => !!value)
                    .join(' · ') || '—'}
                </p>
                <p className="mt-0.5 text-sm text-slate-700">{resource.reason}</p>
                <p className="mt-0.5 text-xs text-slate-500">
                  {resource.assignedBy.fullName} · {formatDateTime(resource.assignedAt)}
                </p>
              </li>
            ))}
          </ul>
        )}
      </DetailCard>
    </div>
  )
}
