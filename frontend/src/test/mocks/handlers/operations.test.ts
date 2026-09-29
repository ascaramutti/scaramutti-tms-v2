import { describe, expect, it } from 'vitest'
import { fakeAdditionalResource, fakeServiceDetail, fakeServiceSummary } from './operations'

/**
 * Los fakes no arman alertas que el backend no produce: si lo hicieran, un test podría afirmar sobre
 * una respuesta imposible sin enterarse. Se prueba la guarda por separado porque ningún test
 * legítimo la hace saltar.
 */
describe('la guarda de los fakes de viajes', () => {
  it.each(['PENDING_ASSIGNMENT', 'COMPLETED', 'CANCELLED', 'DELETED'] as const)(
    'rechaza el viaje marcado en %s',
    (status) => {
      expect(() => fakeServiceSummary({ status, needsReassignment: true })).toThrow(/fake imposible/)
    },
  )

  it.each(['PENDING_START', 'IN_PROGRESS'] as const)('acepta el viaje marcado en %s', (status) => {
    expect(() => fakeServiceSummary({ status, needsReassignment: true })).not.toThrow()
  })

  it('en el detalle, rechaza marcas que no cuadran entre el viaje y sus conductores', () => {
    const conductor = { id: 3, fullName: 'Juan Pérez' }
    // Una marca sin conductor, en el principal y en un refuerzo
    expect(() =>
      fakeServiceDetail({ status: 'IN_PROGRESS', driver: null, driverNeedsReassignment: true }),
    ).toThrow(/sin conductor/)
    expect(() =>
      fakeServiceDetail({
        status: 'IN_PROGRESS',
        additionalResources: [fakeAdditionalResource({ driver: null, driverNeedsReassignment: true })],
      }),
    ).toThrow(/sin conductor/)
    // Un conductor marcado con el viaje sin marcar
    expect(() =>
      fakeServiceDetail({
        status: 'IN_PROGRESS',
        driver: conductor,
        driverNeedsReassignment: true,
        needsReassignment: false,
      }),
    ).toThrow(/sin marcar/)
    // El viaje marcado sin ningún conductor marcado
    expect(() => fakeServiceDetail({ status: 'IN_PROGRESS', needsReassignment: true })).toThrow(
      /sin ningún/,
    )
    // Lo posible: la marca del viaje derivada de un refuerzo
    expect(
      fakeServiceDetail({
        status: 'IN_PROGRESS',
        additionalResources: [fakeAdditionalResource({ driverNeedsReassignment: true })],
      }).needsReassignment,
    ).toBe(true)
  })
})
