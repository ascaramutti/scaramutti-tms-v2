import { describe, expect, it } from 'vitest'
import { ROLES } from '../../test/mocks/handlers/workers'
import { assignableRoles, sessionRankLevel } from './rank'

describe('sessionRankLevel', () => {
  it('el admin no tiene techo', () => {
    expect(sessionRankLevel('admin', ROLES)).toBe(Infinity)
    expect(sessionRankLevel('admin', undefined)).toBe(Infinity)
  })

  it('el resto toma el nivel de su rol, cruzado por el nombre de sistema', () => {
    expect(sessionRankLevel('finance_manager', ROLES)).toBe(2)
    expect(sessionRankLevel('general_manager', ROLES)).toBe(3)
  })

  it('un rol ausente del catálogo, o sin catálogo, vale cero', () => {
    expect(sessionRankLevel('finance_manager', ROLES.filter((role) => role.name !== 'finance_manager'))).toBe(0)
    expect(sessionRankLevel('finance_manager', undefined)).toBe(0)
    expect(sessionRankLevel(undefined, ROLES)).toBe(0)
  })
})

describe('assignableRoles', () => {
  it('ofrece solo los de nivel estrictamente menor', () => {
    expect(assignableRoles(ROLES, 2).map((role) => role.name)).toEqual([
      'warehouse_keeper',
      'driver',
      'escort',
      'assistant',
      'operator',
    ])
  })

  it('sin techo ofrece todos; con cero, ninguno', () => {
    expect(assignableRoles(ROLES, Infinity)).toHaveLength(ROLES.length)
    expect(assignableRoles(ROLES, 0)).toEqual([])
  })
})
