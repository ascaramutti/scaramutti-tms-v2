import { isValidElement, type ReactNode } from 'react'
import type { RouteObject } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { UserRole } from '../../api'
import { routes } from '../../router'
import { canRoleOpenPath } from './canRoleOpenPath'
import { ProtectedRoute } from './ProtectedRoute'
import { ALL_ROLES } from './roleLanding'

function allowedRolesOf(element: ReactNode): UserRole[] | undefined {
  let node: ReactNode = element
  while (isValidElement<{ children?: ReactNode; allowedRoles?: UserRole[] }>(node)) {
    if (node.type === ProtectedRoute) return node.props.allowedRoles
    node = node.props.children
  }
  return undefined
}

function moduleRoutes(table: RouteObject[]): { path: string; allowedRoles: UserRole[] }[] {
  return table.flatMap((route) => {
    const allowedRoles = route.path === undefined ? undefined : allowedRolesOf(route.element)
    const own = route.path !== undefined && allowedRoles ? [{ path: route.path, allowedRoles }] : []
    return [...own, ...(route.children ? moduleRoutes(route.children) : [])]
  })
}

// Las dos listas se escriben por separado: sin esto, una ruta nueva sin su fila en
// canRoleOpenPath manda a "Sin acceso" a quien llega por un enlace guardado.
describe('canRoleOpenPath y el router', () => {
  const rutas = moduleRoutes(routes)

  it('recorre las rutas de los módulos', () => {
    expect(rutas.length).toBeGreaterThan(20)
  })

  it.each(rutas.map((ruta) => [ruta.path, ruta.allowedRoles] as const))(
    '%s abre para los mismos cargos que su guarda',
    (path, allowedRoles) => {
      const concrete = path.replace(/:[^/]+/g, '7')
      expect(ALL_ROLES.filter((role) => canRoleOpenPath(concrete, role)).sort()).toEqual(
        [...allowedRoles].sort(),
      )
    },
  )
})
