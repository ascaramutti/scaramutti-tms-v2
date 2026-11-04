import { http, HttpResponse } from 'msw'
import type { UserAccountSummaryResponse } from '../../../api'

const API = 'http://localhost:8080/api/v1'

export function fakeUserAccount(overrides: Partial<UserAccountSummaryResponse> = {}): UserAccountSummaryResponse {
  return {
    id: 12,
    username: 'jperez',
    worker: { id: 31, fullName: 'Juan Pérez Quispe', isActive: true },
    role: {
      name: 'operations_manager',
      description: 'Gerente de Operaciones',
      level: 3,
      canLogin: true,
      driverProfile: 'NONE',
    },
    isActive: true,
    ...overrides,
  }
}

export const usersHandlers = [
  http.get(`${API}/users`, ({ request }) => {
    const isActive = new URL(request.url).searchParams.get('isActive')
    const accounts = [
      fakeUserAccount(),
      fakeUserAccount({ id: 13, username: 'mrios', isActive: false }),
    ]
    if (isActive === null) return HttpResponse.json(accounts)
    return HttpResponse.json(accounts.filter((account) => String(account.isActive) === isActive))
  }),
]
