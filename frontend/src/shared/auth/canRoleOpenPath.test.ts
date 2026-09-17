import { describe, expect, it } from 'vitest'
import { canRoleOpenPath } from './canRoleOpenPath'
import {
  CHANGE_PASSWORD_PATH,
  CLIENTS_BASE,
  OPERATIONS_BASE,
  QUOTATIONS_BASE,
  WAREHOUSE_BASE,
} from '../paths'

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

  it.each([
    '//evil.com',
    '/\\evil.com',
    'https://evil.com',
    'javascript:alert(1)',
    'sin-barra',
    '  /almacen',
    // Un carácter de control entre las dos barras: el parser de URL lo tira y lo
    // que queda sale del dominio.
    '/\r/evil.com',
    '/\n/evil.com',
    '/\t/evil.com',
    '/\u0000/evil.com',
  ])(
    'no acepta %j como destino: no es una ruta de esta aplicación',
    (destino) => {
      expect(canRoleOpenPath(destino, 'admin')).toBe(false)
    },
  )

  it.each([
    QUOTATIONS_BASE,
    `${QUOTATIONS_BASE}/12`,
    `${WAREHOUSE_BASE}/entradas?pagina=2`,
    `${QUOTATIONS_BASE}/12#items`,
    // Un no-ASCII válido tiene que pasar: si alguien estira el rango de control
    // hasta 0xFF, los acentos empiezan a rechazarse y nadie se entera.
    `${WAREHOUSE_BASE}/entradas?q=señal`,
  ])(
    'sigue aceptando %s, que es una ruta legítima',
    (destino) => {
      expect(canRoleOpenPath(destino, 'admin')).toBe(true)
    },
  )

  it('sin rol no abre nada', () => {
    expect(canRoleOpenPath(QUOTATIONS_BASE, undefined)).toBe(false)
  })
  /**
   * El maestro de clientes. Las filas negativas son las que detectan que falte la
   * regla: sin ella `canRoleOpenPath` cae en su "si no hay regla, que pase" y un
   * vendedor con un enlace guardado a clientes aterriza en "Sin acceso", que es
   * exactamente lo que esta función existe para evitar. Las positivas impiden que
   * alguien "arregle" eso poniendo una lista vacía.
   */
  it.each(['admin', 'general_manager', 'operations_manager'] as const)(
    '%s abre el maestro de clientes',
    (role) => {
      expect(canRoleOpenPath(CLIENTS_BASE, role)).toBe(true)
    },
  )

  it.each(['sales', 'dispatcher', 'finance_manager', 'warehouse_keeper'] as const)(
    '%s no abre el maestro de clientes',
    (role) => {
      expect(canRoleOpenPath(CLIENTS_BASE, role)).toBe(false)
    },
  )

  it('el formulario de un cliente hereda el permiso de la búsqueda', () => {
    expect(canRoleOpenPath(`${CLIENTS_BASE}/7/editar`, 'operations_manager')).toBe(true)
    expect(canRoleOpenPath(`${CLIENTS_BASE}/7/editar`, 'sales')).toBe(false)
  })

  /** Una ruta que empieza igual pero es otro segmento no hereda nada. */
  it('no confunde una ruta que solo comparte el comienzo', () => {
    expect(canRoleOpenPath(`${CLIENTS_BASE}X`, 'sales')).toBe(true)
  })

})
