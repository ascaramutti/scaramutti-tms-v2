import { describe, expect, it } from 'vitest'
import type { SessionUser, UserAccountDetailResponse, UserRole } from '../../api'
import { fakeUserAccountDetail } from '../../test/mocks/handlers/users'
import { ROLES } from '../../test/mocks/handlers/workers'
import { sessionRankLevel } from '../workers/rank'
import { accountActions } from './accountActions'

const SESION_ID = 1
const TRABAJADOR_DE_LA_SESION = 29119

function sesion(role: UserRole): SessionUser {
  return {
    id: SESION_ID,
    username: `user-${role}`,
    fullName: `Usuario ${role}`,
    position: 'Cargo de prueba',
    role,
    isActive: true,
    workerId: TRABAJADOR_DE_LA_SESION,
  }
}

function cuentaDe(roleName: string, overrides: Partial<UserAccountDetailResponse> = {}): UserAccountDetailResponse {
  const role = ROLES.find((candidate) => candidate.name === roleName)
  if (!role) throw new Error(`Cargo sin fixture: ${roleName}`)
  return fakeUserAccountDetail({ role, ...overrides })
}

function accionesDe(role: UserRole, account: UserAccountDetailResponse) {
  return accountActions(account, sesion(role), sessionRankLevel(role, ROLES))
}

const NADA = { canResetPassword: false, canDeactivate: false, canReactivate: false, reactivateBlockedBy: null }

describe('accountActions', () => {
  it.each<[UserRole, string, 'la gestiona' | 'no la gestiona']>([
    ['finance_manager', 'warehouse_keeper', 'la gestiona'],
    ['finance_manager', 'sales', 'no la gestiona'],
    ['general_manager', 'finance_manager', 'la gestiona'],
    ['general_manager', 'operations_manager', 'no la gestiona'],
    ['admin', 'admin', 'la gestiona'],
  ])('%s, sobre una cuenta de %s, %s', (sesionRole, cuentaRole, resultado) => {
    const enRango = resultado === 'la gestiona'
    expect(accionesDe(sesionRole, cuentaDe(cuentaRole))).toEqual({
      isOwn: false,
      isOutOfRank: !enRango,
      canResetPassword: enRango,
      canDeactivate: enRango,
      canReactivate: false,
      reactivateBlockedBy: null,
    })
  })

  it('en rango, una inactiva se reactiva y no se desactiva', () => {
    expect(accionesDe('admin', cuentaDe('sales', { isActive: false }))).toMatchObject({
      canResetPassword: true,
      canDeactivate: false,
      canReactivate: true,
      reactivateBlockedBy: null,
    })
  })

  it('sin el nivel de la sesión no ofrece nada y tampoco dice que está fuera de rango', () => {
    expect(accountActions(cuentaDe('warehouse_keeper'), sesion('admin'), undefined)).toEqual({
      isOwn: false,
      isOutOfRank: false,
      ...NADA,
    })
  })

  it.each<[string, boolean]>([
    ['activa', true],
    ['inactiva', false],
  ])('la propia %s no ofrece nada, tampoco a admin, y no está fuera de rango', (_estado, isActive) => {
    expect(accionesDe('admin', cuentaDe('admin', { id: SESION_ID, isActive }))).toEqual({
      isOwn: true,
      isOutOfRank: false,
      ...NADA,
    })
  })

  it('la propia de un cargo con techo tampoco está fuera de rango', () => {
    expect(accionesDe('general_manager', cuentaDe('general_manager', { id: SESION_ID }))).toEqual({
      isOwn: true,
      isOutOfRank: false,
      ...NADA,
    })
  })

  it('la propia se reconoce por la cuenta, no por el trabajador', () => {
    expect(accionesDe('admin', cuentaDe('sales', { id: TRABAJADOR_DE_LA_SESION }))).toMatchObject({
      isOwn: false,
      canDeactivate: true,
    })
  })

  it('sin sesión ninguna cuenta es la propia', () => {
    expect(accountActions(cuentaDe('sales', { id: SESION_ID }), null, Infinity).isOwn).toBe(false)
  })

  it.each<[string, Partial<UserAccountDetailResponse['worker']>, boolean, string | null]>([
    ['con el trabajador de baja', { isActive: false }, true, 'workerInactive'],
    ['con el cargo sin sesión', {}, false, 'roleCannotLogin'],
    ['con las dos, primero el trabajador', { isActive: false }, false, 'workerInactive'],
  ])('reactivar queda trabado %s y no se ofrece', (_caso, worker, canLogin, motivo) => {
    const base = cuentaDe('sales')
    const cuenta = cuentaDe('sales', {
      isActive: false,
      worker: { ...base.worker, ...worker },
      role: { ...base.role, canLogin },
    })
    expect(accionesDe('admin', cuenta)).toEqual({
      isOwn: false,
      isOutOfRank: false,
      canResetPassword: true,
      canDeactivate: false,
      canReactivate: false,
      reactivateBlockedBy: motivo,
    })
  })

  it('una activa con el trabajador de baja se desactiva, sin motivo', () => {
    const base = cuentaDe('sales')
    expect(accionesDe('admin', cuentaDe('sales', { worker: { ...base.worker, isActive: false } }))).toMatchObject({
      canDeactivate: true,
      reactivateBlockedBy: null,
    })
  })

  it('fuera de rango no dice motivo aunque la reactivación estaría trabada', () => {
    const base = cuentaDe('operations_manager')
    const cuenta = cuentaDe('operations_manager', { isActive: false, worker: { ...base.worker, isActive: false } })
    expect(accionesDe('general_manager', cuenta)).toEqual({ isOwn: false, isOutOfRank: true, ...NADA })
  })
})
