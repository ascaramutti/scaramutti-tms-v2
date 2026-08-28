import { useState } from 'react'
import { CheckCircle2, Play, RotateCcw, Trash2, XCircle } from 'lucide-react'
import type { UserRole } from '../../../../api'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import type { ServiceWithEtag } from '../../hooks/useService'
import {
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  availableServiceStatusTransitions,
  isExitTransition,
  type ServiceStatusTransition,
} from '../../status/serviceStatusTransitions'
import { ServiceExitModal } from './ServiceExitModal'
import { ServiceProgressModal } from './ServiceProgressModal'

interface ServiceStatusActionsProps {
  service: ServiceWithEtag
  role: UserRole | undefined
}

const TRANSITION_ICONS: Record<ServiceStatusTransition, typeof Play> = {
  IN_PROGRESS: Play,
  COMPLETED: CheckCircle2,
  CANCELLED: XCircle,
  DELETED: Trash2,
  REOPENED: RotateCcw,
}

/**
 * Las dos salidas se ofrecen en gris: sacan el viaje del circuito, pero no son la acción
 * principal de una pantalla que se mira todo el tiempo, y en rojo teñirían de alarma el
 * encabezado entero. El rojo queda para el botón que confirma dentro del diálogo, que es
 * donde la acción de verdad ocurre.
 *
 * Reabrir no sigue esa regla porque no destruye nada: repara. Es primario en los dos
 * lugares, y en la pantalla de un viaje que ya salió del circuito no compite con nada.
 */
const TRANSITION_BUTTON_STYLES: Record<ServiceStatusTransition, string> = {
  IN_PROGRESS: PRIMARY_BUTTON,
  COMPLETED: PRIMARY_BUTTON,
  CANCELLED: SECONDARY_BUTTON,
  DELETED: SECONDARY_BUTTON,
  REOPENED: PRIMARY_BUTTON,
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

  return (
    <>
      {/* El grupo desaparece cuando no hay nada que ofrecer, pero el diálogo NO cuelga
          de esa condición. Si mientras está abierto el viaje cambia de estado (otro
          usuario lo canceló, y el detalle se refrescó), colgarlo de acá lo haría
          desvanecerse bajo el cursor, con el error adentro y sin que nada lo explique.
          Se cierra cuando el usuario lo cierra. */}
      {transitions.length > 0 && (
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
                className={TRANSITION_BUTTON_STYLES[transition]}
              >
                <Icon size={16} className="mr-2" aria-hidden />
                {SERVICE_STATUS_TRANSITION_PRESENTATION[transition].buttonLabel}
              </button>
            )
          })}
        </div>
      )}

      {/* Dos diálogos, no cinco: los que avanzan el viaje piden fecha y nota, y los que
          lo sacan del circuito o lo devuelven piden versión y motivo. Cada uno recibe un
          tipo que no admite las transiciones del otro, así que cruzarlos no compila. */}
      {openTransition !== null &&
        (isExitTransition(openTransition) ? (
          <ServiceExitModal
            isOpen
            onClose={() => setOpenTransition(null)}
            transition={openTransition}
            service={service}
            role={role}
          />
        ) : (
          <ServiceProgressModal
            isOpen
            onClose={() => setOpenTransition(null)}
            transition={openTransition}
            service={service}
          />
        ))}
    </>
  )
}
