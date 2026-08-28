import { describe, expect, it } from 'vitest'
import type { ServiceStatus, UserRole } from '../../../api'
import { SERVICE_STATUS_VALUES } from './serviceStatusPresentation'
import { fakeAdditionalResource } from '../../../test/mocks/handlers/operations'
import {
  REOPEN_AVAILABLE_NOTE,
  REOPEN_FORCE_WARNING,
  SERVICE_EXIT_FAILURE_MESSAGE,
  SERVICE_EXIT_REASON_LABEL,
  SERVICE_EXIT_TRANSITION_PROMPT,
  SERVICE_PROGRESS_DATE_TIME_LABEL,
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  availableServiceStatusTransitions,
  type ServiceStatusTransition,
  serviceKeepsReopenPath,
} from './serviceStatusTransitions'

/** Las cinco que la pantalla ofrece. */
const TRANSITIONS: ServiceStatusTransition[] = [
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED',
  'DELETED',
  'REOPENED',
]

/** Los siete del contrato, no los cinco del módulo: los dos de más también tienen que
 * quedar afuera de las acciones, y son justamente los que nadie mira. */
const ALL_ROLES: UserRole[] = [
  'admin',
  'sales',
  'dispatcher',
  'general_manager',
  'operations_manager',
  'finance_manager',
  'warehouse_keeper',
]

const OPERATING_ROLES: UserRole[] = [
  'admin',
  'general_manager',
  'operations_manager',
  'dispatcher',
]

describe('availableServiceStatusTransitions, por estado', () => {
  it.each([
    ['PENDING_ASSIGNMENT', ['CANCELLED', 'DELETED']],
    ['PENDING_START', ['IN_PROGRESS', 'CANCELLED', 'DELETED']],
    // Eliminar NO sale de en ruta: lo que ya ocurrió se cancela, no se elimina.
    ['IN_PROGRESS', ['COMPLETED', 'CANCELLED']],
    // El único que no ofrece nada a nadie. Terminar no es un error que haya que deshacer.
    ['COMPLETED', []],
    // Los dos que dejaron de ser el final del camino.
    ['CANCELLED', ['REOPENED']],
    ['DELETED', ['REOPENED']],
  ] as const)('desde %s ofrece %j', (status, expected) => {
    // Igualdad exacta del arreglo y no `toContain`: con `toContain`, ofrecer iniciar
    // sobre un viaje que ya salió a ruta pasaría verde.
    expect(availableServiceStatusTransitions(status, 'admin')).toEqual(expected)
  })

  it('contesta por los seis estados del contrato sin dejar ninguno sin respuesta', () => {
    // Un estado nuevo que nadie agregue a la tabla devolvería `undefined` y reventaría
    // al recorrerlo en la barra. Acá contesta un arreglo vacío, que es una decisión.
    for (const status of SERVICE_STATUS_VALUES) {
      expect(Array.isArray(availableServiceStatusTransitions(status, 'admin'))).toBe(true)
    }
    expect(SERVICE_STATUS_VALUES).toHaveLength(6)
  })
})

describe('availableServiceStatusTransitions, por rol', () => {
  it.each(OPERATING_ROLES)('%s puede iniciar un viaje pendiente de inicio', (role) => {
    expect(availableServiceStatusTransitions('PENDING_START', role)).toContain('IN_PROGRESS')
  })

  it.each(OPERATING_ROLES)('%s puede finalizar un viaje en ruta', (role) => {
    expect(availableServiceStatusTransitions('IN_PROGRESS', role)).toContain('COMPLETED')
  })

  it.each(['admin', 'general_manager'] as const)('%s puede reabrir un viaje cancelado', (role) => {
    expect(availableServiceStatusTransitions('CANCELLED', role)).toEqual(['REOPENED'])
    expect(availableServiceStatusTransitions('DELETED', role)).toEqual(['REOPENED'])
  })

  it('la jefatura de operaciones cancela y elimina, pero NO reabre', () => {
    // Es la única transición que le queda afuera, y la diferencia importa: reabrir es
    // una herramienta de reparación, no del día a día. Se afirma también lo que SÍ puede,
    // para que un veto escrito de más no pase disfrazado.
    expect(availableServiceStatusTransitions('CANCELLED', 'operations_manager')).toEqual([])
    expect(availableServiceStatusTransitions('DELETED', 'operations_manager')).toEqual([])
    expect(availableServiceStatusTransitions('PENDING_START', 'operations_manager')).toEqual([
      'IN_PROGRESS',
      'CANCELLED',
      'DELETED',
    ])
  })

  it('el despacho no elimina, igual que no cancela', () => {
    expect(availableServiceStatusTransitions('PENDING_START', 'dispatcher')).toEqual([
      'IN_PROGRESS',
    ])
    expect(availableServiceStatusTransitions('PENDING_ASSIGNMENT', 'dispatcher')).toEqual([])
    expect(availableServiceStatusTransitions('CANCELLED', 'dispatcher')).toEqual([])
    expect(availableServiceStatusTransitions('DELETED', 'dispatcher')).toEqual([])
  })

  it.each(['admin', 'general_manager', 'operations_manager'] as const)(
    '%s puede cancelar el viaje en los tres estados que lo admiten',
    (role) => {
      for (const status of ['PENDING_ASSIGNMENT', 'PENDING_START', 'IN_PROGRESS'] as const) {
        expect(availableServiceStatusTransitions(status, role)).toContain('CANCELLED')
      }
    },
  )

  it('el despacho avanza el viaje pero no lo cancela, en ninguno de los tres estados', () => {
    // Los tres y no uno: un veto aplicado en la rama de un solo estado pasaría verde
    // con un caso, que es el hueco clásico de una suite que solo mira el delta.
    for (const status of ['PENDING_ASSIGNMENT', 'PENDING_START', 'IN_PROGRESS'] as const) {
      expect(availableServiceStatusTransitions(status, 'dispatcher')).not.toContain('CANCELLED')
    }
    // Y sobre el arreglo COMPLETO, para que un veto escrito de más (que le sacara
    // también el avance) no pase disfrazado.
    expect(availableServiceStatusTransitions('IN_PROGRESS', 'dispatcher')).toEqual(['COMPLETED'])
  })

  it.each(SERVICE_STATUS_VALUES)('ventas no opera el viaje en %s', (status) => {
    // Colgar la barra de los roles del módulo (que incluyen ventas) en vez de los que
    // operan el viaje es un cambio de una sola constante importada.
    expect(availableServiceStatusTransitions(status, 'sales')).toEqual([])
  })

  it('los roles de otros módulos tampoco operan el viaje', () => {
    for (const role of ALL_ROLES.filter((candidate) => !OPERATING_ROLES.includes(candidate))) {
      expect(availableServiceStatusTransitions('IN_PROGRESS', role)).toEqual([])
    }
  })

  it('sin rol no ofrece nada', () => {
    expect(availableServiceStatusTransitions('IN_PROGRESS', undefined)).toEqual([])
  })
})

describe('SERVICE_STATUS_TRANSITION_PRESENTATION', () => {
  it('nombra la cancelación por su acción', () => {
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.CANCELLED.successMessage('SRV-0077')).toBe(
      'SRV-0077 cancelado.',
    )
  })

  it('nombra cada transición por su acción y no por el estado', () => {
    // El usuario confirma haber iniciado o finalizado un viaje, no haber "actualizado
    // un estado". Un texto compartido para las dos no distingue una cosa de la otra.
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.IN_PROGRESS.successMessage('SRV-0077')).toBe(
      'SRV-0077 iniciado. El viaje está en ruta.',
    )
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.COMPLETED.successMessage('SRV-0077')).toBe(
      'SRV-0077 finalizado.',
    )
  })

  it('le da a cada transición su propio botón y su propio título', () => {
    const labels = TRANSITIONS.map(
      (transition) => SERVICE_STATUS_TRANSITION_PRESENTATION[transition].buttonLabel,
    )
    const titles = TRANSITIONS.map(
      (transition) => SERVICE_STATUS_TRANSITION_PRESENTATION[transition].modalTitle,
    )

    // Se cuentan los distintos: comparar de a pares deja de cubrir en cuanto entra una
    // cuarta transición, y agregarla sin textos propios es justo lo que pasa después.
    expect(new Set(labels).size).toBe(TRANSITIONS.length)
    expect(new Set(titles).size).toBe(TRANSITIONS.length)
  })

  it('dice entero lo que el usuario decide en cada salida', () => {
    // Los valores COMPLETOS y no un fragmento: los casos del diálogo buscan con una
    // regex que toca solo la primera oración, así que la segunda mitad de las tres se
    // podía borrar sin que nada cayera. En reabrir esa mitad es lo único que anticipa
    // por qué la acción puede chocar con otro viaje.
    expect(SERVICE_EXIT_TRANSITION_PROMPT.CANCELLED).toBe(
      'Se cancela un viaje que ocurrió y se abortó. Deja de estar en circulación.',
    )
    expect(SERVICE_EXIT_TRANSITION_PROMPT.DELETED).toBe(
      'Se elimina un registro que nunca debió existir. El viaje sale de los listados.',
    )
    expect(SERVICE_EXIT_TRANSITION_PROMPT.REOPENED).toBe(
      'El viaje vuelve al estado que tenía antes de salir del circuito y recupera los recursos que tuviera asignados, si siguen libres.',
    )
  })

  it('dice que hay vuelta atrás sin prometerla en seco', () => {
    // Ocupa el lugar de "terminal", que dejó de ser cierta cuando reabrir existió. Dice
    // "en general" porque queda un caso que la pantalla no puede ver: un viaje traído del
    // sistema anterior, sin rastro de dónde venía, tampoco se reabre. El de los refuerzos
    // sí se ve, y por eso se calla la frase entera (`serviceKeepsReopenPath`).
    expect(REOPEN_AVAILABLE_NOTE).toBe('En general se puede reabrir después.')
    // Y vive aparte del prompt, porque solo se le muestra a quien puede reabrir.
    expect(SERVICE_EXIT_TRANSITION_PROMPT.CANCELLED).not.toContain('reabrir')
    expect(SERVICE_EXIT_TRANSITION_PROMPT.DELETED).not.toContain('reabrir')
  })

  it('deja de haber vuelta atrás en cuanto el viaje tiene un refuerzo', () => {
    // El servidor rechaza reabrir un viaje con refuerzos, y darlos de baja exige el viaje
    // en ruta: una vez fuera del circuito el bloqueo es definitivo. Es el único límite de
    // la reapertura que la pantalla puede ver, y por eso es el único que se calla.
    expect(serviceKeepsReopenPath({ additionalResources: [] })).toBe(true)
    expect(
      serviceKeepsReopenPath({ additionalResources: [fakeAdditionalResource()] }),
    ).toBe(false)
  })

  it('dice qué se acepta al forzar la reapertura', () => {
    // Es lo único que cierra la contradicción entre el texto (el viaje vuelve con sus
    // recursos) y la tabla de conflictos (esos recursos están en otro viaje). Se afirma
    // completo: medido por un fragmento, el final es borrable sin que nada falle.
    expect(REOPEN_FORCE_WARNING).toBe(
      'Al reabrir de todos modos, los recursos en conflicto quedan asignados a más de un viaje a la vez.',
    )
  })

  it('nombra la acción que falló en cada salida', () => {
    // Es lo que se muestra cuando el servidor no manda nada. Los tres valores exactos y
    // no solo el conteo de distintos: dos mensajes pueden diferir entre sí y aun así
    // nombrar la acción equivocada.
    const mensajes = Object.values(SERVICE_EXIT_FAILURE_MESSAGE)

    expect(SERVICE_EXIT_FAILURE_MESSAGE.CANCELLED).toBe(
      'No se pudo cancelar el viaje. Intenta de nuevo.',
    )
    expect(SERVICE_EXIT_FAILURE_MESSAGE.DELETED).toBe(
      'No se pudo eliminar el viaje. Intenta de nuevo.',
    )
    expect(SERVICE_EXIT_FAILURE_MESSAGE.REOPENED).toBe(
      'No se pudo reabrir el viaje. Intenta de nuevo.',
    )
    expect(new Set(mensajes).size).toBe(mensajes.length)
  })

  it('rotula el motivo nombrando la acción de cada salida', () => {
    const labels = Object.values(SERVICE_EXIT_REASON_LABEL)

    expect(SERVICE_EXIT_REASON_LABEL.CANCELLED).toBe('Motivo de la cancelación')
    expect(SERVICE_EXIT_REASON_LABEL.DELETED).toBe('Motivo de la eliminación')
    expect(SERVICE_EXIT_REASON_LABEL.REOPENED).toBe('Motivo de la reapertura')
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('avisa lo que pasó, distinto en cada transición', () => {
    // `submitLabel`, `pendingLabel` y `successMessage` no entraban en el conteo de
    // distintos de más abajo, así que dos transiciones podían compartirlos.
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.DELETED.successMessage('SRV-0077')).toBe(
      'SRV-0077 eliminado.',
    )
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.REOPENED.successMessage('SRV-0077')).toBe(
      'SRV-0077 reabierto.',
    )
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.DELETED.pendingLabel).toBe('Eliminando…')
    expect(SERVICE_STATUS_TRANSITION_PRESENTATION.REOPENED.pendingLabel).toBe('Reabriendo…')
    const submits = TRANSITIONS.map((t) => SERVICE_STATUS_TRANSITION_PRESENTATION[t].submitLabel)
    const pendings = TRANSITIONS.map((t) => SERVICE_STATUS_TRANSITION_PRESENTATION[t].pendingLabel)

    expect(new Set(submits).size).toBe(TRANSITIONS.length)
    expect(new Set(pendings).size).toBe(TRANSITIONS.length)
  })

  it('rotula el campo de fecha por lo que cada transición fija', () => {
    // Los valores exactos y no "que sean distintos": intercambiarlos deja dos textos
    // distintos igual, y el verdugo de ese cruce vivía en otro archivo. Una tabla se
    // mide en su propio archivo, si no un corredor apuntado solo acá da un SOBREVIVE
    // que no es cierto.
    expect(SERVICE_PROGRESS_DATE_TIME_LABEL.IN_PROGRESS).toBe('Fecha y hora de inicio')
    expect(SERVICE_PROGRESS_DATE_TIME_LABEL.COMPLETED).toBe('Fecha y hora de fin')
  })
})

describe('la tabla cubre lo que el tipo declara', () => {
  it('presenta todas las transiciones que se ofrecen', () => {
    // Agregar un destino al union sin darle textos deja un botón sin nombre.
    const statuses: ServiceStatus[] = [
      'PENDING_ASSIGNMENT',
      'PENDING_START',
      'IN_PROGRESS',
      'CANCELLED',
      'DELETED',
    ]
    for (const status of statuses) {
      for (const transition of availableServiceStatusTransitions(status, 'admin')) {
        expect(SERVICE_STATUS_TRANSITION_PRESENTATION[transition].buttonLabel).toBeTruthy()
      }
    }
  })
})
