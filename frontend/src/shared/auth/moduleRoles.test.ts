import { describe, expect, it } from 'vitest'
import {
  OPERATIONS_ROLES,
  SERVICE_OPERATE_ROLES,
  QUOTATION_ROLES,
  SERVICES_REPORT_ROLES,
  SERVICE_CREATE_ROLES,
  WAREHOUSE_ROLES,
} from './moduleRoles'
import {
  ALL_ROLES,
  ALMACEN_LANDING,
  COTIZACIONES_LANDING,
  OPERACIONES_LANDING,
  landingPathFor,
} from './roleLanding'

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
    // Primera de las dos excepciones intramódulo: el reporte muestra precios por
    // viaje y el despacho no ve importes.
    expect([...SERVICES_REPORT_ROLES].sort()).toEqual([
      'admin',
      'general_manager',
      'operations_manager',
      'sales',
    ])
    expect(SERVICES_REPORT_ROLES).not.toContain('dispatcher')
  })

  it('registrar un servicio deja afuera al despachador', () => {
    // Segunda excepción intramódulo, y por otro motivo que la del reporte: el alta
    // obliga a mandar el precio, así que quien no puede verlo tampoco lo escribe.
    expect([...SERVICE_CREATE_ROLES].sort()).toEqual([
      'admin',
      'general_manager',
      'operations_manager',
      'sales',
    ])
    expect(SERVICE_CREATE_ROLES).not.toContain('dispatcher')
  })
})

describe('aterrizaje vs permisos del módulo', () => {
  const ROLES_DEL_LANDING = {
    [COTIZACIONES_LANDING]: QUOTATION_ROLES,
    [ALMACEN_LANDING]: WAREHOUSE_ROLES,
    [OPERACIONES_LANDING]: OPERATIONS_ROLES,
  }

  it('cada rol tiene permiso sobre el módulo donde aterriza', () => {
    // Fija que las constantes son consistentes entre sí: que el módulo donde
    // cae cada rol lo tenga en su lista. No lee `router.tsx`, así que no ve una
    // ruta declarada con la lista equivocada; de eso se ocupa `router.test.tsx`,
    // que monta la tabla real, y hoy solo cubre la ruta de operaciones.
    for (const role of ALL_ROLES) {
      const landing = landingPathFor(role)
      expect(ROLES_DEL_LANDING[landing]).toContain(role)
    }
  })
})

describe('SERVICE_OPERATE_ROLES', () => {
  it('son los cuatro que operan el viaje', () => {
    // Escritos a mano y no derivados de otra lista: el esperado no puede salir de lo
    // que se está probando.
    expect([...SERVICE_OPERATE_ROLES].sort()).toEqual([
      'admin',
      'dispatcher',
      'general_manager',
      'operations_manager',
    ])
  })

  it('deja afuera a ventas, que sí entra al módulo', () => {
    // Es exactamente la diferencia con `OPERATIONS_ROLES`, y la razón de que esta
    // lista exista: ventas ve el detalle entero y no opera el viaje.
    expect(SERVICE_OPERATE_ROLES).not.toContain('sales')
    expect(OPERATIONS_ROLES).toContain('sales')
  })

  it('no comparte el arreglo con la lista de registrar', () => {
    // Dos reglas distintas que hoy ni siquiera coinciden: registrar deja afuera al
    // despacho y operar deja afuera a ventas.
    expect(SERVICE_OPERATE_ROLES).not.toBe(SERVICE_CREATE_ROLES)
  })
})
