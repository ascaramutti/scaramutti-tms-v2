import { describe, expect, it } from 'vitest'
import {
  COTIZACIONES_LANDING,
  V1_LANDING,
  isExternalLanding,
  landingPathFor,
} from './roleLanding'

describe('landingPathFor', () => {
  it.each([
    ['admin', COTIZACIONES_LANDING],
    ['sales', COTIZACIONES_LANDING],
    ['general_manager', COTIZACIONES_LANDING],
    ['operations_manager', COTIZACIONES_LANDING],
    ['dispatcher', V1_LANDING],
  ] as const)('%s aterriza en %s', (role, expected) => {
    expect(landingPathFor(role)).toBe(expected)
  })

  it('sin rol (sesión a medio cargar) cae a cotizaciones', () => {
    expect(landingPathFor(undefined)).toBe(COTIZACIONES_LANDING)
  })
})

describe('isExternalLanding', () => {
  it('la raíz del dominio (v1) es externa a la SPA', () => {
    expect(isExternalLanding(V1_LANDING)).toBe(true)
  })

  it('los paths de cotizaciones son internos', () => {
    expect(isExternalLanding(COTIZACIONES_LANDING)).toBe(false)
    expect(isExternalLanding('/cotizaciones/nueva')).toBe(false)
  })
})
