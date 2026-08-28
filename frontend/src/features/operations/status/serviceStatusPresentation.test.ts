import { describe, expect, it } from 'vitest'
import { SERVICE_STATUS_PRESENTATION, SERVICE_STATUS_VALUES } from './serviceStatusPresentation'

describe('SERVICE_STATUS_PRESENTATION, los colores', () => {
  it('no le da el mismo color a dos estados', () => {
    // Mata la CLASE entera y no un caso: cualquier par que comparta variante cae acá,
    // sea el que ya pasó ("en ruta" y "completado" en dos verdes vecinos, que en una
    // pastilla chica se leían igual) o el que venga cuando se agregue un estado.
    const variants = SERVICE_STATUS_VALUES.map(
      (status) => SERVICE_STATUS_PRESENTATION[status].badgeVariant,
    )

    expect(new Set(variants).size).toBe(SERVICE_STATUS_VALUES.length)
  })

  it('reserva el verde para el final feliz', () => {
    // El acuerdo detrás del cambio: verde es "terminó bien", y nada más.
    expect(SERVICE_STATUS_PRESENTATION.COMPLETED.badgeVariant).toBe('success')
    expect(SERVICE_STATUS_PRESENTATION.IN_PROGRESS.badgeVariant).toBe('violet')
  })

  it('sigue la progresión del viaje sin repetir familia de color', () => {
    // Gris sin recursos, azul listo para salir, violeta en movimiento, verde terminado,
    // y las dos salidas malas aparte.
    expect(SERVICE_STATUS_PRESENTATION.PENDING_ASSIGNMENT.badgeVariant).toBe('slate')
    expect(SERVICE_STATUS_PRESENTATION.PENDING_START.badgeVariant).toBe('info')
    expect(SERVICE_STATUS_PRESENTATION.CANCELLED.badgeVariant).toBe('danger')
    expect(SERVICE_STATUS_PRESENTATION.DELETED.badgeVariant).toBe('warning')
  })

  it('le da a cada estado su etiqueta en es-PE, sin repetir', () => {
    const labels = SERVICE_STATUS_VALUES.map((status) => SERVICE_STATUS_PRESENTATION[status].label)

    expect(new Set(labels).size).toBe(SERVICE_STATUS_VALUES.length)
    expect(SERVICE_STATUS_PRESENTATION.IN_PROGRESS.label).toBe('En ruta')
    expect(SERVICE_STATUS_PRESENTATION.COMPLETED.label).toBe('Completado')
  })
})
