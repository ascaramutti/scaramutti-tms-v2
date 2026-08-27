import { describe, expect, it } from 'vitest'
import type { ServiceStatus, UserRole } from '../../../api'
import { SERVICE_STATUS_VALUES } from './serviceStatusPresentation'
import {
  SERVICE_PROGRESS_DATE_TIME_LABEL,
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  availableServiceStatusTransitions,
  type ServiceStatusTransition,
} from './serviceStatusTransitions'

/** Las tres que la pantalla ofrece hoy. Eliminar y reabrir llegan en su propio cambio. */
const TRANSITIONS: ServiceStatusTransition[] = ['IN_PROGRESS', 'COMPLETED', 'CANCELLED']

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
    ['PENDING_ASSIGNMENT', ['CANCELLED']],
    ['PENDING_START', ['IN_PROGRESS', 'CANCELLED']],
    ['IN_PROGRESS', ['COMPLETED', 'CANCELLED']],
    ['COMPLETED', []],
    ['CANCELLED', []],
    ['DELETED', []],
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
    expect(availableServiceStatusTransitions('PENDING_START', 'dispatcher')).toEqual([
      'IN_PROGRESS',
    ])
    expect(availableServiceStatusTransitions('IN_PROGRESS', 'dispatcher')).toEqual(['COMPLETED'])
    expect(availableServiceStatusTransitions('PENDING_ASSIGNMENT', 'dispatcher')).toEqual([])
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
    const statuses: ServiceStatus[] = ['PENDING_ASSIGNMENT', 'PENDING_START', 'IN_PROGRESS']
    for (const status of statuses) {
      for (const transition of availableServiceStatusTransitions(status, 'admin')) {
        expect(SERVICE_STATUS_TRANSITION_PRESENTATION[transition].buttonLabel).toBeTruthy()
      }
    }
  })
})
