import { describe, expect, it } from 'vitest'
import { canRoleOpenPath } from './canRoleOpenPath'
import { CHANGE_PASSWORD_PATH, OPERATIONS_BASE, QUOTATIONS_BASE, WAREHOUSE_BASE } from '../paths'

describe('canRoleOpenPath', () => {
  it.each([
    [QUOTATIONS_BASE, 'admin', true],
    [QUOTATIONS_BASE, 'sales', true],
    [QUOTATIONS_BASE, 'dispatcher', false],
    [QUOTATIONS_BASE, 'warehouse_keeper', false],
    [WAREHOUSE_BASE, 'warehouse_keeper', true],
    [WAREHOUSE_BASE, 'finance_manager', true],
    [WAREHOUSE_BASE, 'dispatcher', false],
    [WAREHOUSE_BASE, 'sales', false],
    [OPERATIONS_BASE, 'dispatcher', true],
    [OPERATIONS_BASE, 'admin', true],
    [OPERATIONS_BASE, 'warehouse_keeper', false],
  ] as const)('%s con rol %s → %s', (path, role, esperado) => {
    expect(canRoleOpenPath(path, role)).toBe(esperado)
  })

  it('una ruta más profunda hereda el permiso de su módulo', () => {
    expect(canRoleOpenPath(`${WAREHOUSE_BASE}/entradas/7/editar`, 'warehouse_keeper')).toBe(true)
    expect(canRoleOpenPath(`${WAREHOUSE_BASE}/entradas/7/editar`, 'dispatcher')).toBe(false)
  })

  it('una ruta que empieza igual pero es otro segmento NO hereda el permiso', () => {
    // Sin borde de segmento, `/almacenamiento` heredaría los roles de almacén.
    expect(canRoleOpenPath(`${WAREHOUSE_BASE}amiento`, 'dispatcher')).toBe(true)
  })

  it('fuera de los tres módulos deja pasar: lo resuelve el router', () => {
    // La cuenta la abre cualquiera con sesión, y una ruta inexistente la manda el
    // comodín a la principal del rol. Decir que no acá le sacaría al usuario un
    // destino que sí podía abrir.
    expect(canRoleOpenPath(CHANGE_PASSWORD_PATH, 'dispatcher')).toBe(true)
    expect(canRoleOpenPath('/no-existe', 'warehouse_keeper')).toBe(true)
  })

  it.each([
    [`${OPERATIONS_BASE}/servicios/nuevo`, 'dispatcher', false],
    [`${OPERATIONS_BASE}/servicios/12/editar`, 'dispatcher', false],
    [`${OPERATIONS_BASE}/servicios/12`, 'dispatcher', true],
    [OPERATIONS_BASE, 'dispatcher', true],
    [`${OPERATIONS_BASE}/servicios/nuevo`, 'sales', true],
    [`${OPERATIONS_BASE}/servicios/12/editar`, 'sales', true],
  ] as const)(
    'dentro de operaciones, %s con rol %s → %s',
    (path, role, esperado) => {
      // Alta y edición de un servicio tocan el precio: el despachador entra al
      // módulo pero no a esas dos.
      expect(canRoleOpenPath(path, role)).toBe(esperado)
    },
  )

  it.each(['//evil.com', '/\\evil.com', 'https://evil.com', 'javascript:alert(1)', 'sin-barra', '  /almacen'])(
    'no acepta %s como destino: no es una ruta de esta aplicación',
    (destino) => {
      expect(canRoleOpenPath(destino, 'admin')).toBe(false)
    },
  )

  it('sin rol no abre nada', () => {
    expect(canRoleOpenPath(QUOTATIONS_BASE, undefined)).toBe(false)
  })
})
