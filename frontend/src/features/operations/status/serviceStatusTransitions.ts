import type { ServiceDetailResponse, ServiceStatus, UserRole } from '../../../api'
import { canOperateService } from './operationsPermissions'

/**
 * Los cinco destinos que el endpoint acepta, y que esta pantalla ofrece.
 *
 * Agregar uno es sumar una entrada acá y una fila en cada tabla de abajo; las que son
 * `Record` sobre este tipo no compilan hasta que estén todas.
 */
export type ServiceStatusTransition = ServiceProgressTransition | ServiceExitTransition

/**
 * Las tres que sacan el viaje del circuito o lo devuelven. Piden lo mismo: la versión del
 * recurso y un motivo, y ninguna fecha el viaje, porque fechan la decisión y esa marca la
 * pone el servidor.
 */
export type ServiceExitTransition = 'CANCELLED' | 'DELETED' | 'REOPENED'

/**
 * Las dos que hacen AVANZAR el viaje, y las únicas que le fijan una marca de tiempo
 * real. Tienen tipo propio para que el formulario que pide esa fecha no pueda recibir
 * por descuido una transición que no la lleva: ahí el rótulo del campo no existiría.
 */
export type ServiceProgressTransition = 'IN_PROGRESS' | 'COMPLETED'

/**
 * Desde cada estado, qué transiciones se pueden pedir.
 *
 * Es la tabla de ARCOS de la máquina del servidor recortada a lo que esta pantalla
 * ofrece, más las dos filas de la reapertura, que allá no son arcos: el servidor las
 * resuelve aparte, mirando el rastro de dónde venía el viaje. Ojo al copiarla de allá
 * por simetría: eliminar existe desde los dos pendientes pero NO desde un viaje en
 * ruta.
 *
 * El único estado que no figura, y que por lo tanto no ofrece nada, es completado.
 *
 * "Pendiente de asignación" sí figura, pero solo con la salida: desde ahí el viaje no
 * avanza pidiendo un estado sino asignándole recursos, que es otra acción y vive en
 * otra ficha. Cancelar tiene que estar igual, porque la salida hace falta justamente
 * para los viajes que quedaron a medias.
 *
 * El orden de cada fila es el orden en que se muestran los botones.
 */
const TRANSITIONS_BY_STATUS: Partial<Record<ServiceStatus, readonly ServiceStatusTransition[]>> = {
  PENDING_ASSIGNMENT: ['CANCELLED', 'DELETED'],
  PENDING_START: ['IN_PROGRESS', 'CANCELLED', 'DELETED'],
  // Eliminar NO sale de acá, y no es un olvido: eliminar etiqueta el registro que nunca
  // debió existir, y un viaje que ya salió a ruta ocurrió de verdad. Lo que ocurrió se
  // cancela; lo que nunca fue se elimina.
  IN_PROGRESS: ['COMPLETED', 'CANCELLED'],
  // Los dos que dejaron de ser el final del camino. Completado no figura: terminar no es
  // un error que haya que deshacer, y si los datos están mal se corrigen editando, que el
  // endpoint sigue permitiendo en ese estado.
  CANCELLED: ['REOPENED'],
  DELETED: ['REOPENED'],
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
 * Avanzar el viaje no lo veta nadie: el despacho lo inicia y lo cierra. Lo que no decide
 * es sacarlo del circuito ni devolverlo, así que las tres salidas son las que tienen
 * vetados.
 */
const VETOED_ROLES: Record<ServiceStatusTransition, readonly UserRole[]> = {
  IN_PROGRESS: [],
  COMPLETED: [],
  CANCELLED: ['dispatcher'],
  DELETED: ['dispatcher'],
  // La lista más larga de la tabla, y deja afuera también a la jefatura de operaciones:
  // reabrir es una herramienta de reparación, no una operación del día a día. Existe
  // porque una cancelación por error, sin esto, sería permanente.
  REOPENED: ['operations_manager', 'dispatcher'],
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
 * tabla de textos porque las tres salidas no fechan el viaje sino la decisión, y esa
 * marca la pone el servidor: ahí no hay campo que rotular.
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
  DELETED: {
    buttonLabel: 'Eliminar viaje',
    modalTitle: 'Eliminar viaje',
    submitLabel: 'Eliminar viaje',
    pendingLabel: 'Eliminando…',
    successMessage: (serviceCode) => `${serviceCode} eliminado.`,
  },
  REOPENED: {
    buttonLabel: 'Reabrir viaje',
    modalTitle: 'Reabrir viaje',
    submitLabel: 'Reabrir viaje',
    pendingLabel: 'Reabriendo…',
    successMessage: (serviceCode) => `${serviceCode} reabierto.`,
  },
}

/**
 * Lo que el usuario está decidiendo, dicho en el diálogo antes de que confirme.
 *
 * Cancelar y eliminar terminan los dos con el viaje fuera del circuito, y la diferencia
 * entre ellos no se ve en el resultado sino en lo que se está afirmando: uno dice que el
 * viaje ocurrió y se abortó, el otro que nunca debió existir. Es la decisión, no el
 * efecto, y por eso va escrita donde se toma.
 *
 * Reabrir no nombra a dónde vuelve el viaje, y no es por prudencia: el detalle no trae de
 * dónde viene. El estado sale del rastro de auditoría, del lado del servidor, y llega
 * recién en la respuesta.
 */
export const SERVICE_EXIT_TRANSITION_PROMPT: Record<ServiceExitTransition, string> = {
  CANCELLED: 'Se cancela un viaje que ocurrió y se abortó. Deja de estar en circulación.',
  DELETED: 'Se elimina un registro que nunca debió existir. El viaje sale de los listados.',
  REOPENED:
    'El viaje vuelve al estado que tenía antes de salir del circuito y recupera los recursos que tuviera asignados, si siguen libres.',
}

/**
 * Si la transición saca el viaje del circuito o lo devuelve, en vez de hacerlo avanzar.
 *
 * Es lo que decide cuál de los dos diálogos se abre.
 *
 * Se pregunta por la tabla de textos y no por una lista aparte, y la diferencia importa:
 * esa tabla es un `Record` sobre el tipo, así que una transición nueva no compila hasta
 * tener su fila, y de ahí sale la respuesta. Con una lista suelta el día que alguien
 * agregara un destino la lista compilaría sin él, esta función devolvería `false`, y la
 * transición caería sola del lado del AVANCE: se abriría el formulario que pide fecha
 * sobre algo que no la lleva, en silencio.
 */
export function isExitTransition(
  transition: ServiceStatusTransition,
): transition is ServiceExitTransition {
  return transition in SERVICE_EXIT_TRANSITION_PROMPT
}

/**
 * Cómo se rotula el motivo en cada salida.
 *
 * Nombra la acción en vez de decir "Motivo" a secas: es el único campo del diálogo, y
 * el rótulo es lo que queda a la vista mientras se escribe. Se perdió al unificar los
 * tres diálogos en uno y se restituye acá, que es donde vive el resto del texto.
 */
export const SERVICE_EXIT_REASON_LABEL: Record<ServiceExitTransition, string> = {
  CANCELLED: 'Motivo de la cancelación',
  DELETED: 'Motivo de la eliminación',
  REOPENED: 'Motivo de la reapertura',
}

/**
 * Qué decir cuando el servidor no manda nada que mostrar (red caída, o un 500 sin
 * cuerpo). Nombra la acción que falló, que es lo que el usuario necesita para saber si
 * reintentar: un texto genérico lo deja sin saber qué quedó a medias.
 *
 * Se escribe entero y no se arma con el rótulo del botón: conjugarlo a mano da frases
 * que no se leen en español.
 */
export const SERVICE_EXIT_FAILURE_MESSAGE: Record<ServiceExitTransition, string> = {
  CANCELLED: 'No se pudo cancelar el viaje. Intenta de nuevo.',
  DELETED: 'No se pudo eliminar el viaje. Intenta de nuevo.',
  REOPENED: 'No se pudo reabrir el viaje. Intenta de nuevo.',
}

/**
 * Si el viaje admite que le corrijan los datos.
 *
 * Los dos estados que salieron del circuito son inmutables y el servidor los rechaza con
 * `OPS-004`. Todos los demás se editan, el completado incluido: corregir un viaje ya
 * cerrado es justamente para lo que existe el endpoint.
 *
 * Se escribe como la lista de lo que NO se edita y no al revés, por lo mismo que los
 * vetos de las transiciones: un estado nuevo nace editable, que es la respuesta correcta
 * para cualquier estado del circuito, en vez de nacer bloqueado sin que nadie lo decida.
 */
export function isServiceEditable(status: ServiceStatus): boolean {
  return !SERVICE_EXITED_STATUSES.includes(status)
}

/** Los estados desde los que un viaje ya no se opera: salió del circuito. */
const SERVICE_EXITED_STATUSES: readonly ServiceStatus[] = ['CANCELLED', 'DELETED']

/**
 * Que hay vuelta atrás, dicho solo a quien la tiene.
 *
 * Cuelga del permiso y no de una frase fija, y esa es toda la decisión: la jefatura de
 * operaciones cancela y elimina pero está vetada de reabrir, así que leía una promesa que
 * su propio rol le niega, y en el viaje ya cancelado tampoco iba a ver el botón. Un texto
 * que promete una capacidad tiene que salir de la misma fuente que decide esa capacidad.
 *
 * Dice "en general" porque queda un caso en que tampoco se cumple para quien sí puede:
 * un viaje que llegó del sistema anterior sin rastro de dónde venía no se reabre, y ese
 * dato no está en la pantalla. El de los refuerzos sí está, y por eso no se cubre con el
 * hedge sino callando la frase entera: ver `serviceKeepsReopenPath`. No anticipar un
 * límite autoriza a callar, no a afirmar lo contrario, y menos cuando la pérdida es
 * definitiva y el dato para saberlo ya está en pantalla.
 */
export const REOPEN_AVAILABLE_NOTE = 'En general se puede reabrir después.'

/**
 * Si al viaje todavía le queda el camino de vuelta, mirando lo único que la pantalla
 * puede saber sin preguntar.
 *
 * Un viaje con recursos de REFUERZO no se reabre nunca: el servidor lo rechaza y la baja
 * de un refuerzo exige el viaje en ruta, así que una vez fuera del circuito no hay forma
 * de deshacer el bloqueo. No se avisa de ese callejón (esa decisión sigue en pie), pero
 * tampoco se promete lo contrario, que es lo que pasaba: la frase salía por rol y este
 * viaje es justo aquel en que la promesa no se puede cumplir.
 */
export function serviceKeepsReopenPath(service: Pick<ServiceDetailResponse, 'additionalResources'>) {
  return service.additionalResources.length === 0
}

/**
 * Lo que el usuario acepta al forzar, dicho donde lo decide.
 *
 * Solo aplica al reabrir, que es la única transición que mueve recursos. Sin esta línea
 * la pantalla se contradice sola: el texto de arriba promete que el viaje vuelve con sus
 * recursos y la tabla de al lado dice que otro viaje ya se los llevó. Acá no hay tercera
 * salida (los recursos no se eligen: son los que el viaje tenía), así que lo único que
 * falta para decidir es qué queda después.
 */
export const REOPEN_FORCE_WARNING =
  'Al reabrir de todos modos, los recursos en conflicto quedan asignados a más de un viaje a la vez.'
