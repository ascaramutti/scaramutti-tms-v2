import { describe, expect, it } from 'vitest'
import type { UserRole } from '../../../api'
import {
  canCreateCatalogEntry,
  canCreateService,
  canSeeServicePrices,
} from './operationsPermissions'

describe('canSeeServicePrices', () => {
  it.each(['admin', 'sales', 'general_manager', 'operations_manager'] as const)(
    '%s ve los importes',
    (role) => {
      expect(canSeeServicePrices(role)).toBe(true)
    },
  )

  it('el despacho no ve los importes', () => {
    expect(canSeeServicePrices('dispatcher')).toBe(false)
  })

  it('sin sesión resuelta todavía, no se arma la columna', () => {
    // El instante entre montar la pantalla y que `useAuth` devuelva el usuario.
    // Sin esta guarda, ese primer render arma una columna de precios que después
    // habría que sacar.
    expect(canSeeServicePrices(undefined)).toBe(false)
  })

  it.each(['finance_manager', 'warehouse_keeper'] as const)(
    '%s no hereda el permiso por no estar en la lista',
    (role) => {
      // Hoy no llegan a la pantalla (la ruta los filtra antes), pero la regla se
      // escribe en positivo justamente para que un rol nuevo no entre por defecto.
      expect(canSeeServicePrices(role as UserRole)).toBe(false)
    },
  )
})

describe('canCreateService', () => {
  it.each(['admin', 'sales', 'general_manager', 'operations_manager'] as const)(
    '%s registra servicios',
    (role) => {
      expect(canCreateService(role)).toBe(true)
    },
  )

  it('el despacho no registra: el alta exige el precio, que no puede ver', () => {
    expect(canCreateService('dispatcher')).toBe(false)
  })

  it('sin sesión resuelta todavía, no se ofrece el botón', () => {
    expect(canCreateService(undefined)).toBe(false)
  })

  it.each(['finance_manager', 'warehouse_keeper'] as const)(
    '%s no hereda el permiso por no estar en la lista',
    (role) => {
      expect(canCreateService(role as UserRole)).toBe(false)
    },
  )
})

describe('canCreateCatalogEntry', () => {
  it.each(['admin', 'sales', 'general_manager', 'operations_manager'] as const)(
    '%s da de alta un cliente o un tipo de carga al vuelo',
    (role) => {
      expect(canCreateCatalogEntry(role)).toBe(true)
    },
  )

  it('el despacho no crea catálogos: el servidor le niega los dos POST', () => {
    expect(canCreateCatalogEntry('dispatcher')).toBe(false)
  })

  it('sin sesión resuelta todavía, no se ofrece el atajo', () => {
    expect(canCreateCatalogEntry(undefined)).toBe(false)
  })
})
