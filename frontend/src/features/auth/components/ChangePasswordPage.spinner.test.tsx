import { CHANGE_PASSWORD_PATH, QUOTATIONS_BASE } from '../../../shared/paths'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ChangePasswordPage } from './ChangePasswordPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import { fakeChangedPasswordResponse } from '../../../test/mocks/handlers/auth'

const spinner = vi.hoisted(() => {
  let abrir!: () => void
  const abierta = new Promise<void>((resolve) => {
    abrir = resolve
  })
  return { abrir, abierta }
})

// El spinner mínimo, retenido por el test: en producción dura 1 s, y en los demás tests, 0. Va en su
// propio archivo porque el reemplazo vale para todo el archivo.
vi.mock('../../../shared/utils/withMinDuration', () => ({
  withMinDuration: async <T,>(promise: Promise<T>) => {
    const value = await promise
    await spinner.abierta
    return value
  },
}))

describe('la sesión nueva se guarda apenas vuelve el cambio', () => {
  it('con el spinner mínimo todavía girando, los tokens nuevos ya están guardados', async () => {
    tokenStorage.setTokens('fake-access', 'fake-refresh')
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <MemoryRouter initialEntries={[CHANGE_PASSWORD_PATH]}>
            <Routes>
              <Route path={CHANGE_PASSWORD_PATH} element={<ChangePasswordPage />} />
              <Route path={QUOTATIONS_BASE} element={<div>HOME</div>} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/^contraseña actual$/i), 'Password123')
    await user.type(screen.getByLabelText(/^nueva contraseña$/i), 'NuevoPassword456')
    await user.type(screen.getByLabelText(/^confirmar nueva contraseña$/i), 'NuevoPassword456')
    await user.click(screen.getByRole('button', { name: /cambiar contraseña/i }))
    try {
      await waitFor(() => expect(tokenStorage.getRefreshToken()).toBe(fakeChangedPasswordResponse.refreshToken))
      expect(tokenStorage.getAccessToken()).toBe(fakeChangedPasswordResponse.token)
      expect(screen.queryByText('HOME')).not.toBeInTheDocument()
    } finally {
      spinner.abrir()
    }
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })
})
