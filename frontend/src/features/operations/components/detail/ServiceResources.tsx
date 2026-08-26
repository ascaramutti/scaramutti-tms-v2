import { useState } from 'react'
import { Plus, Truck } from 'lucide-react'
import type { ServiceDetailResponse } from '../../../../api'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import { formatDateTime } from '../../../../shared/utils/formatters'
import { describeAdditionalResource } from '../../status/resourcePresentation'
import { AddResourcesModal } from '../resources/AddResourcesModal'
import { AssignResourcesModal } from '../resources/AssignResourcesModal'
import { RemoveResourceDialog } from '../resources/RemoveResourceDialog'
import { DetailCard, Field } from './DetailCard'

interface ServiceResourcesProps {
  service: ServiceDetailResponse
  /**
   * `true` si el rol puede operar el viaje. Se calcula en la página, que ya tiene la
   * sesión, y baja como prop para que esta ficha siga siendo de presentación.
   */
  canOperate: boolean
}

/**
 * Recursos del viaje: los principales (conductor, tracto, carreta) y los refuerzos
 * sumados en ruta.
 *
 * Los tres principales son null mientras el viaje esté pendiente de asignación, y
 * la carreta puede seguir en null después porque es opcional: hay carga que no la
 * lleva. El guion dice eso, no que falte un dato.
 *
 * Una fila de refuerzo es un PEDIDO y no un recurso suelto: puede traer los tres a
 * la vez, uno solo o cualquier combinación, y por eso se lista completa en vez de una
 * línea por recurso.
 *
 * La acción de asignar se ofrece SOLO desde "pendiente de asignación", que es el
 * único estado desde el que el servidor la acepta. En los demás no se muestra
 * deshabilitada sino que no se muestra: un botón gris no explica por qué está gris, y
 * el estado del viaje ya está en el encabezado de la pantalla.
 */
export function ServiceResources({ service, canOperate }: ServiceResourcesProps) {
  const [isAssignOpen, setIsAssignOpen] = useState(false)
  const [isAddOpen, setIsAddOpen] = useState(false)
  // Qué refuerzo se está por quitar. Se guarda la FILA y no solo su id porque el
  // diálogo repite de cuál se trata, que es lo que evita el clic errado.
  const [resourceToRemove, setResourceToRemove] =
    useState<ServiceDetailResponse['additionalResources'][number] | null>(null)
  const canAssign = canOperate && service.status === 'PENDING_ASSIGNMENT'
  // Los refuerzos solo existen sobre un viaje EN RUTA: son el relevo de un conductor
  // que agotó su descanso o la unidad que sale a un varado, y ninguna de las dos
  // cosas ocurre sobre un viaje que todavía no salió o que ya terminó.
  const canReinforce = canOperate && service.status === 'IN_PROGRESS'

  return (
    <div className="space-y-4">
      <DetailCard
        title="Recursos asignados"
        headingId="service-resources-heading"
        action={
          canAssign ? (
            <button
              type="button"
              onClick={() => setIsAssignOpen(true)}
              className={`${PRIMARY_BUTTON} shrink-0`}
            >
              <Truck className="mr-2 h-4 w-4" aria-hidden="true" />
              Asignar recursos
            </button>
          ) : undefined
        }
      >
        <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Field label="Conductor" value={service.driver?.fullName ?? '—'} />
          <Field label="Tracto" value={service.tractor?.plate ?? '—'} />
          <Field label="Carreta" value={service.trailer?.plate ?? '—'} />
        </dl>
      </DetailCard>

      <AssignResourcesModal
        isOpen={isAssignOpen}
        onClose={() => setIsAssignOpen(false)}
        serviceId={service.id}
        serviceCode={service.code}
      />

      <AddResourcesModal
        isOpen={isAddOpen}
        onClose={() => setIsAddOpen(false)}
        serviceId={service.id}
        serviceCode={service.code}
      />

      {resourceToRemove && (
        <RemoveResourceDialog
          isOpen
          onClose={() => setResourceToRemove(null)}
          serviceId={service.id}
          serviceCode={service.code}
          resource={resourceToRemove}
        />
      )}

      <DetailCard
        title="Refuerzos"
        headingId="service-additional-heading"
        action={
          canReinforce ? (
            <button
              type="button"
              onClick={() => setIsAddOpen(true)}
              className={`${SECONDARY_BUTTON} shrink-0`}
            >
              <Plus className="mr-2 h-4 w-4" aria-hidden="true" />
              Agregar refuerzo
            </button>
          ) : undefined
        }
      >
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
                <div className="flex items-start justify-between gap-3">
                  <p className="text-sm text-slate-900">
                    {describeAdditionalResource(resource)}
                  </p>
                  {canReinforce && (
                    <button
                      type="button"
                      onClick={() => setResourceToRemove(resource)}
                      // El nombre accesible incluye A QUIÉN quita: tres botones
                      // "Quitar" idénticos son indistinguibles para un lector de
                      // pantalla, que es justo donde el clic errado no tiene vuelta.
                      aria-label={`Quitar refuerzo: ${describeAdditionalResource(resource)}`}
                      // `-m-1 p-1` agranda el objetivo de clic sin correr la fila:
                      // el texto solo mide 16px de alto.
                      className="-m-1 shrink-0 rounded p-1 text-xs font-medium text-red-700 hover:text-red-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
                    >
                      Quitar
                    </button>
                  )}
                </div>
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
