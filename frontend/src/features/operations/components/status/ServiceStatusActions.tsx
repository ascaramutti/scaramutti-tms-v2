import { useState } from 'react'
import { CheckCircle2, Play } from 'lucide-react'
import type { UserRole } from '../../../../api'
import { PRIMARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import type { ServiceWithEtag } from '../../hooks/useService'
import {
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  availableServiceStatusTransitions,
  type ServiceStatusTransition,
} from '../../status/serviceStatusTransitions'
import { ServiceProgressModal } from './ServiceProgressModal'

interface ServiceStatusActionsProps {
  service: ServiceWithEtag
  role: UserRole | undefined
}

const TRANSITION_ICONS: Record<ServiceStatusTransition, typeof Play> = {
  IN_PROGRESS: Play,
  COMPLETED: CheckCircle2,
}

/**
 * Las acciones que mueven el viaje de estado, junto al badge que lo muestra.
 *
 * Viven en el encabezado y no en una ficha porque son verbos del estado, y el estado se
 * lee ahí mismo. Lo que el viaje no admite no se muestra deshabilitado: no se muestra.
 * Un botón gris no explica por qué está gris, y el badge de al lado ya dice en qué
 * estado está el viaje.
 *
 * Sin transiciones disponibles no se renderiza nada, ni siquiera el contenedor: un grupo
 * vacío deja en el encabezado un espacio que nada explica.
 */
export function ServiceStatusActions({ service, role }: ServiceStatusActionsProps) {
  const [openTransition, setOpenTransition] = useState<ServiceStatusTransition | null>(null)
  const transitions = availableServiceStatusTransitions(service.status, role)

  if (transitions.length === 0) {
    return null
  }

  return (
    <>
      <div
        role="group"
        aria-label="Acciones del viaje"
        className="flex flex-wrap items-center gap-2"
      >
        {transitions.map((transition) => {
          const Icon = TRANSITION_ICONS[transition]
          return (
            <button
              key={transition}
              type="button"
              onClick={() => setOpenTransition(transition)}
              className={PRIMARY_BUTTON}
            >
              <Icon size={16} className="mr-2" aria-hidden />
              {SERVICE_STATUS_TRANSITION_PRESENTATION[transition].buttonLabel}
            </button>
          )
        })}
      </div>

      {openTransition !== null && (
        <ServiceProgressModal
          isOpen
          onClose={() => setOpenTransition(null)}
          transition={openTransition}
          service={service}
        />
      )}
    </>
  )
}
