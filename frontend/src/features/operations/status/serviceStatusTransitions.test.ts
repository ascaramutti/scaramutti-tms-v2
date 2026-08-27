import { describe, expect, it } from 'vitest'
import type { ServiceStatus, UserRole } from '../../../api'
import { SERVICE_STATUS_VALUES } from './serviceStatusPresentation'
import {
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  availableServiceStatusTransitions,
} from './serviceStatusTransitions'

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
    ['PENDING_ASSIGNMENT', []],
    ['PENDING_START', ['IN_PROGRESS']],
    ['IN_PROGRESS', ['COMPLETED']],
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
    expect(availableServiceStatusTransitions('PENDING_START', role)).toEqual(['IN_PROGRESS'])
  })

  it.each(OPERATING_ROLES)('%s puede finalizar un viaje en ruta', (role) => {
    expect(availableServiceStatusTransitions('IN_PROGRESS', role)).toEqual(['COMPLETED'])
  })

  it('el despacho avanza el viaje: no está vetado en ninguna de las dos', () => {
    // Se afirma sobre el arreglo COMPLETO y no con `toContain`: un veto escrito de
    // más, que le sacara al despacho también el avance, pasaría con `toContain`.
    expect(availableServiceStatusTransitions('PENDING_START', 'dispatcher')).toEqual([
      'IN_PROGRESS',
    ])
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
    const inProgress = SERVICE_STATUS_TRANSITION_PRESENTATION.IN_PROGRESS
    const completed = SERVICE_STATUS_TRANSITION_PRESENTATION.COMPLETED

    expect(inProgress.buttonLabel).not.toBe(completed.buttonLabel)
    expect(inProgress.modalTitle).not.toBe(completed.modalTitle)
    expect(inProgress.dateTimeLabel).not.toBe(completed.dateTimeLabel)
  })
})

describe('la tabla cubre lo que el tipo declara', () => {
  it('presenta las dos transiciones que se ofrecen', () => {
    // Agregar un destino al union sin darle textos deja un botón sin nombre.
    const statuses: ServiceStatus[] = ['PENDING_START', 'IN_PROGRESS']
    for (const status of statuses) {
      for (const transition of availableServiceStatusTransitions(status, 'admin')) {
        expect(SERVICE_STATUS_TRANSITION_PRESENTATION[transition].buttonLabel).toBeTruthy()
      }
    }
  })
})
