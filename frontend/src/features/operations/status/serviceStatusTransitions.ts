import type { ServiceStatus, UserRole } from '../../../api'
import { canOperateService } from './operationsPermissions'

/**
 * Los destinos que esta pantalla ofrece pedir.
 *
 * El endpoint acepta cinco (eliminar y reabrir son los otros dos) y llegan en su propio
 * cambio. Agregarlos es sumar una entrada acá y una fila en la tabla de abajo.
 */
export type ServiceStatusTransition = 'IN_PROGRESS' | 'COMPLETED'

/**
 * Desde cada estado, qué transiciones se pueden pedir.
 *
 * Es la tabla de ARCOS y espeja la del servidor. Los estados que no figuran no ofrecen
 * nada, y eso incluye tres casos bien distintos entre sí: los terminales (completado,
 * cancelado y eliminado), y "pendiente de asignación", desde donde el viaje avanza
 * asignándole recursos y no pidiendo un estado.
 *
 * El orden de cada fila es el orden en que se muestran los botones.
 */
const TRANSITIONS_BY_STATUS: Partial<Record<ServiceStatus, readonly ServiceStatusTransition[]>> = {
  PENDING_START: ['IN_PROGRESS'],
  IN_PROGRESS: ['COMPLETED'],
}

/**
 * Roles que NO pueden pedir cada transición, aunque el endpoint los deje entrar.
 *
 * Se escribe en negativo y no como "estos roles pueden", igual que el servidor. La razón
 * es que los roles de un usuario son un conjunto: con una lista de permitidos, alguien
 * que sumara dos roles pasaría por el segundo aunque el primero se lo prohibiera, y la
 * regla diría lo contrario de lo que significa.
 *
 * Hoy el contrato entrega un solo rol por usuario, así que las dos formas se comportan
 * igual y ningún test las distingue. Se escribe así de todos modos: la regla sobrevive
 * al día en que un usuario tenga dos roles, y ese día no avisa.
 *
 * Avanzar el viaje no lo veta nadie. La primera transición con vetados es cancelar.
 */
const VETOED_ROLES: Record<ServiceStatusTransition, readonly UserRole[]> = {
  IN_PROGRESS: [],
  COMPLETED: [],
}

/**
 * Las transiciones que este rol puede pedir sobre un viaje en este estado.
 *
 * Son dos preguntas y las dos hacen falta: desde dónde se puede llegar al destino (la
 * tabla de arcos) y quién puede pedirlo (operar el viaje, menos los vetados). La
 * garantía es del servidor; acá se decide qué botones se arman, para no ofrecer un
 * camino que termina en un 403.
 */
export function availableServiceStatusTransitions(
  status: ServiceStatus,
  role: UserRole | undefined,
): ServiceStatusTransition[] {
  if (!canOperateService(role)) {
    return []
  }
  const transitions = TRANSITIONS_BY_STATUS[status] ?? []
  return transitions.filter(
    (transition) => role === undefined || !VETOED_ROLES[transition].includes(role),
  )
}

interface ServiceStatusTransitionPresentation {
  /** Texto del botón en la barra de acciones del detalle. */
  buttonLabel: string
  /** Título del diálogo, que además es su nombre accesible. */
  modalTitle: string
  /** Rótulo del campo de fecha y hora real. */
  dateTimeLabel: string
  /** Texto del botón que confirma, y el que lo reemplaza mientras el pedido viaja. */
  submitLabel: string
  pendingLabel: string
  /** Confirmación que se muestra al terminar. Nombra la acción y no "el estado": lo que
   * el usuario quiere ver confirmado es que el viaje arrancó o cerró. */
  successMessage: (serviceCode: string) => string
}

export const SERVICE_STATUS_TRANSITION_PRESENTATION: Record<
  ServiceStatusTransition,
  ServiceStatusTransitionPresentation
> = {
  IN_PROGRESS: {
    buttonLabel: 'Iniciar viaje',
    modalTitle: 'Iniciar viaje',
    dateTimeLabel: 'Fecha y hora de inicio',
    submitLabel: 'Iniciar viaje',
    pendingLabel: 'Iniciando…',
    successMessage: (serviceCode) => `${serviceCode} iniciado. El viaje está en ruta.`,
  },
  COMPLETED: {
    buttonLabel: 'Finalizar viaje',
    modalTitle: 'Finalizar viaje',
    dateTimeLabel: 'Fecha y hora de fin',
    submitLabel: 'Finalizar viaje',
    pendingLabel: 'Finalizando…',
    successMessage: (serviceCode) => `${serviceCode} finalizado.`,
  },
}
