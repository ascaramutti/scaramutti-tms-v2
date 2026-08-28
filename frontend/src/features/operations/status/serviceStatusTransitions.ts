import type { ServiceStatus, UserRole } from '../../../api'
import { canOperateService } from './operationsPermissions'

/**
 * Los destinos que esta pantalla ofrece pedir.
 *
 * El endpoint acepta cinco (eliminar y reabrir son los otros dos) y llegan en su propio
 * cambio. Agregarlos es sumar una entrada acá y una fila en la tabla de abajo.
 */
export type ServiceStatusTransition = ServiceProgressTransition | 'CANCELLED'

/**
 * Las dos que hacen AVANZAR el viaje, y las únicas que le fijan una marca de tiempo
 * real. Tienen tipo propio para que el formulario que pide esa fecha no pueda recibir
 * por descuido una transición que no la lleva: ahí el rótulo del campo no existiría.
 */
export type ServiceProgressTransition = 'IN_PROGRESS' | 'COMPLETED'

/**
 * Desde cada estado, qué transiciones se pueden pedir.
 *
 * Es la tabla de ARCOS RECORTADA a lo que esta pantalla ofrece: un subconjunto de la
 * del servidor, sin el arco que no se pide por este endpoint y sin los destinos que
 * llegan en su propio cambio. Ojo al copiarla de allá por simetría: eliminar existe
 * desde los dos pendientes pero NO desde un viaje en ruta. Los estados que no figuran no ofrecen
 * nada, y son los tres terminales: completado, cancelado y eliminado.
 *
 * "Pendiente de asignación" sí figura, pero solo con la salida: desde ahí el viaje no
 * avanza pidiendo un estado sino asignándole recursos, que es otra acción y vive en
 * otra ficha. Cancelar tiene que estar igual, porque la salida hace falta justamente
 * para los viajes que quedaron a medias.
 *
 * El orden de cada fila es el orden en que se muestran los botones.
 */
const TRANSITIONS_BY_STATUS: Partial<Record<ServiceStatus, readonly ServiceStatusTransition[]>> = {
  PENDING_ASSIGNMENT: ['CANCELLED'],
  PENDING_START: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['COMPLETED', 'CANCELLED'],
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
 * Avanzar el viaje no lo veta nadie: el despacho lo inicia y lo cierra. Lo que no
 * decide es matarlo, y por eso cancelar es la única con vetados.
 */
const VETOED_ROLES: Record<ServiceStatusTransition, readonly UserRole[]> = {
  IN_PROGRESS: [],
  COMPLETED: [],
  CANCELLED: ['dispatcher'],
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
  return transitions.filter((transition) => !VETOED_ROLES[transition].includes(role))
}

interface ServiceStatusTransitionPresentation {
  /** Texto del botón en la barra de acciones del detalle. */
  buttonLabel: string
  /** Título del diálogo, que además es su nombre accesible. */
  modalTitle: string
  /** Texto del botón que confirma, y el que lo reemplaza mientras el pedido viaja. */
  submitLabel: string
  pendingLabel: string
  /** Confirmación que se muestra al terminar. Nombra la acción y no "el estado": lo que
   * el usuario quiere ver confirmado es que el viaje arrancó o cerró. */
  successMessage: (serviceCode: string) => string
}

/**
 * Cómo se rotula el campo de fecha, y solo en las dos que lo tienen. Vive aparte de la
 * tabla de textos porque cancelar no fecha el viaje sino la decisión, y esa marca la
 * pone el servidor: ahí no hay campo que rotular.
 */
export const SERVICE_PROGRESS_DATE_TIME_LABEL: Record<ServiceProgressTransition, string> = {
  IN_PROGRESS: 'Fecha y hora de inicio',
  COMPLETED: 'Fecha y hora de fin',
}

export const SERVICE_STATUS_TRANSITION_PRESENTATION: Record<
  ServiceStatusTransition,
  ServiceStatusTransitionPresentation
> = {
  IN_PROGRESS: {
    buttonLabel: 'Iniciar viaje',
    modalTitle: 'Iniciar viaje',
    submitLabel: 'Iniciar viaje',
    pendingLabel: 'Iniciando…',
    successMessage: (serviceCode) => `${serviceCode} iniciado. El viaje está en ruta.`,
  },
  COMPLETED: {
    buttonLabel: 'Finalizar viaje',
    modalTitle: 'Finalizar viaje',
    submitLabel: 'Finalizar viaje',
    pendingLabel: 'Finalizando…',
    successMessage: (serviceCode) => `${serviceCode} finalizado.`,
  },
  CANCELLED: {
    buttonLabel: 'Cancelar viaje',
    modalTitle: 'Cancelar viaje',
    submitLabel: 'Cancelar viaje',
    pendingLabel: 'Cancelando…',
    successMessage: (serviceCode) => `${serviceCode} cancelado.`,
  },
}
