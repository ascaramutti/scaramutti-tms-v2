import { describe, expect, it } from 'vitest'
import {
  OPERATIONS_ROLES,
  QUOTATION_ROLES,
  SERVICES_REPORT_ROLES,
  WAREHOUSE_ROLES,
} from './moduleRoles'

/**
 * Estas listas son el espejo de `x-required-roles` del contrato OpenAPI. El
 * backend es la autoridad (responde 403), pero si acá sobra un rol la UI le
 * ofrece un módulo que va a rebotar, y si falta uno le esconde su trabajo.
 *
 * Se fijan por contenido exacto, no por "incluye": agregar un rol al módulo es
 * una decisión que tiene que romper este test y obligar a mirar el contrato.
 */
describe('roles por módulo', () => {
  it('operaciones: los cuatro del listado más el despachador', () => {
    // `dispatcher` entra por el listado y el detalle de viajes; el reporte
    // semanal lo excluye, y eso lo hace cumplir el backend, no el menú.
    expect([...OPERATIONS_ROLES].sort()).toEqual([
      'admin',
      'dispatcher',
      'general_manager',
      'operations_manager',
      'sales',
    ])
  })

  it('el despachador trabaja únicamente en operaciones', () => {
    expect(QUOTATION_ROLES).not.toContain('dispatcher')
    expect(WAREHOUSE_ROLES).not.toContain('dispatcher')
  })

  it('los roles de almacén no entran a operaciones', () => {
    expect(OPERATIONS_ROLES).not.toContain('finance_manager')
    expect(OPERATIONS_ROLES).not.toContain('warehouse_keeper')
  })

  it('el reporte semanal deja afuera al despachador', () => {
    // Es la única excepción intramódulo: el reporte muestra precios por viaje y
    // el despacho no ve importes.
    expect([...SERVICES_REPORT_ROLES].sort()).toEqual([
      'admin',
      'general_manager',
      'operations_manager',
      'sales',
    ])
    expect(SERVICES_REPORT_ROLES).not.toContain('dispatcher')
  })
})
