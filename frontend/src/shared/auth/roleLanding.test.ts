import { describe, expect, it } from 'vitest'
import {
  ALMACEN_LANDING,
  COTIZACIONES_LANDING,
  OPERACIONES_LANDING,
  V1_LANDING,
  isExternalLanding,
  landingPathFor,
} from './roleLanding'
import type { UserRole } from '../../api'

describe('landingPathFor', () => {
  it.each([
    ['admin', COTIZACIONES_LANDING],
    ['sales', COTIZACIONES_LANDING],
    ['general_manager', COTIZACIONES_LANDING],
    ['operations_manager', COTIZACIONES_LANDING],
    ['dispatcher', OPERACIONES_LANDING],
    ['finance_manager', ALMACEN_LANDING],
    ['warehouse_keeper', ALMACEN_LANDING],
  ] as const)('%s aterriza en %s', (role, expected) => {
    expect(landingPathFor(role)).toBe(expected)
  })

  it('sin rol (sesión a medio cargar) cae a cotizaciones', () => {
    expect(landingPathFor(undefined)).toBe(COTIZACIONES_LANDING)
  })

  it('ningún rol aterriza fuera de la SPA', () => {
    // Con operaciones adentro de v2 no queda un solo rol que salga a v1. Si
    // alguno vuelve a apuntar afuera, es una decisión y tiene que romper acá.
    const roles: UserRole[] = [
      'admin',
      'sales',
      'general_manager',
      'operations_manager',
      'dispatcher',
      'finance_manager',
      'warehouse_keeper',
    ]
    for (const role of roles) {
      expect(isExternalLanding(landingPathFor(role))).toBe(false)
    }
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

  it('almacén es interno: vive en esta SPA, se navega con el router', () => {
    expect(isExternalLanding(ALMACEN_LANDING)).toBe(false)
  })

  it('operaciones es interno: se recodificó dentro de esta SPA', () => {
    expect(isExternalLanding(OPERACIONES_LANDING)).toBe(false)
  })
})
