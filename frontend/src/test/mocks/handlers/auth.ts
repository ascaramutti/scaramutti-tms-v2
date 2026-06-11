import { http, HttpResponse } from 'msw'
import type { LoginResponse, Problem, UserResponse } from '../../../api'

const API = 'http://localhost:8080/api/v1'

export const fakeUser: UserResponse = {
  id: 1,
  username: 'admin',
  fullName: 'Admin TMS',
  position: 'Administrador del sistema',
  role: 'admin',
  isActive: true,
}

export const fakeLoginResponse: LoginResponse = {
  token: 'fake-access-token',
  refreshToken: 'fake-refresh-token',
  expiresAt: '2026-12-31T23:59:59Z',
  expiresIn: 3600,
  user: fakeUser,
}

export const authHandlers = [
  http.post(`${API}/auth/login`, () => HttpResponse.json(fakeLoginResponse)),
  http.get(`${API}/auth/me`, () => HttpResponse.json(fakeUser)),
  // 204 No Content (success default para change-password)
  http.post(`${API}/auth/change-password`, () => new HttpResponse(null, { status: 204 })),
]

// Helpers para tests que quieren respuestas de error puntuales.
export function loginErrorResponse(status: number, problem: Problem) {
  return http.post(`${API}/auth/login`, () =>
    HttpResponse.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}

export function getCurrentUserErrorResponse(status: number, problem: Problem) {
  return http.get(`${API}/auth/me`, () =>
    HttpResponse.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}

export function changePasswordErrorResponse(status: number, problem: Problem) {
  return http.post(`${API}/auth/change-password`, () =>
    HttpResponse.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}
