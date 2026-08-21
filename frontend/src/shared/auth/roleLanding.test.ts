import { describe, expect, it } from 'vitest'
import {
  ALL_ROLES,
  ALMACEN_LANDING,
  COTIZACIONES_LANDING,
  OPERACIONES_LANDING,
  landingLabelFor,
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

  it('el despachador aterriza en la ruta del módulo, escrita a mano', () => {
    // A propósito con el literal y no con la constante: el resto del archivo
    // compara la constante contra sí misma, así que un cambio de path no
    // rompería nada acá. Este caso es el que lo fija.
    expect(landingPathFor('dispatcher')).toBe('/cotizaciones/operaciones')
  })

  it('sin rol (sesión a medio cargar) cae a cotizaciones', () => {
    expect(landingPathFor(undefined)).toBe(COTIZACIONES_LANDING)
  })

  it('un rol que el frontend todavía no conoce cae a cotizaciones', () => {
    // El backend puede sumar un rol antes que la UI. El cast salta el chequeo
    // de tipos a propósito: en runtime llega el string que mande el servidor, y
    // lo que no puede pasar es que el usuario quede navegando a `undefined`.
    expect(landingPathFor('rol_nuevo_del_backend' as UserRole)).toBe(COTIZACIONES_LANDING)
  })

  it('ningún rol aterriza fuera de la SPA', () => {
    // Con operaciones adentro de v2 no queda un rol que trabaje en otra app. De
    // esto depende que el aterrizaje pueda navegar SIEMPRE con el router: si
    // alguno vuelve a apuntar afuera hay que reponer la navegación externa que
    // se retiró, y este test tiene que romper antes de que alguien quede en una
    // ruta que el router no sabe resolver.
    for (const role of ALL_ROLES) {
      expect(landingPathFor(role)).toMatch(/^\/cotizaciones(\/|$)/)
    }
  })
})

describe('landingLabelFor', () => {
  it.each([
    ['admin', 'Cotizaciones'],
    ['dispatcher', 'Operaciones'],
    ['finance_manager', 'Almacén'],
  ] as const)('%s ve "%s" en el link de salida', (role, expected) => {
    expect(landingLabelFor(role)).toBe(expected)
  })

  it('todos los roles tienen nombre visible', () => {
    // El compilador ya exige una etiqueta por destino; esto lo comprueba en
    // runtime y de paso deja escrito cuáles son los nombres esperados.
    for (const role of ALL_ROLES) {
      expect(landingLabelFor(role)).toMatch(/^(Cotizaciones|Almacén|Operaciones)$/)
    }
  })

  it('sin rol cae al nombre del landing por defecto', () => {
    expect(landingLabelFor(undefined)).toBe('Cotizaciones')
  })

  it('un rol que el frontend todavía no conoce muestra el nombre por defecto', () => {
    expect(landingLabelFor('rol_nuevo_del_backend' as UserRole)).toBe('Cotizaciones')
  })
})
