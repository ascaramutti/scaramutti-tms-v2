import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { toast } from 'sonner'
import { handleApiFormError } from './handleApiFormError'

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
  },
}))

const mockToastError = vi.mocked(toast.error)

function makeAxiosError(status: number, data: unknown): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, null, {
    status,
    data,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  })
}

describe('handleApiFormError', () => {
  beforeEach(() => {
    mockToastError.mockClear()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('si NO es AxiosError, muestra toast genérico con icono y no llama setError', () => {
    const setError = vi.fn()
    handleApiFormError(new Error('boom'), {
      setError,
      fallbackMessage: 'fallback',
    })
    expect(setError).not.toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith(
      'Error inesperado. Intentá de nuevo.',
      expect.objectContaining({ icon: expect.anything() }),
    )
  })

  it('Problem.code matchea codeFieldMap → asigna detail al field configurado', () => {
    const setError = vi.fn()
    const error = makeAxiosError(400, {
      code: 'AUTH-004',
      detail: 'La contraseña actual es incorrecta',
    })
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'fallback',
      codeFieldMap: { 'AUTH-004': 'currentPassword' },
    })
    expect(setError).toHaveBeenCalledWith('currentPassword', {
      type: 'backend',
      message: 'La contraseña actual es incorrecta',
    })
    expect(mockToastError).not.toHaveBeenCalled()
  })

  it('codeFieldMap matchea independiente del status (409, 422, etc.)', () => {
    const setError = vi.fn()
    const error = makeAxiosError(409, {
      code: 'CLI-001',
      detail: 'El RUC ya existe en otro cliente',
    })
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'fallback',
      codeFieldMap: { 'CLI-001': 'taxId' },
    })
    expect(setError).toHaveBeenCalledWith('taxId', {
      type: 'backend',
      message: 'El RUC ya existe en otro cliente',
    })
    expect(mockToastError).not.toHaveBeenCalled()
  })

  it('Problem.code en codeFieldMap sin detail → usa fallback de "Error de validación"', () => {
    const setError = vi.fn()
    const error = makeAxiosError(400, { code: 'AUTH-004' })
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'fallback',
      codeFieldMap: { 'AUTH-004': 'currentPassword' },
    })
    expect(setError).toHaveBeenCalledWith('currentPassword', {
      type: 'backend',
      message: 'Error de validación',
    })
  })

  it('400 con errors[] dentro de allowedFields → asigna cada uno a setError', () => {
    const setError = vi.fn()
    const error = makeAxiosError(400, {
      code: 'COM-001',
      errors: [
        { field: 'username', message: 'Requerido' },
        { field: 'password', message: 'Muy corta' },
      ],
    })
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'fallback',
      allowedFields: ['username', 'password'],
    })
    expect(setError).toHaveBeenNthCalledWith(1, 'username', {
      type: 'backend',
      message: 'Requerido',
    })
    expect(setError).toHaveBeenNthCalledWith(2, 'password', {
      type: 'backend',
      message: 'Muy corta',
    })
    expect(mockToastError).not.toHaveBeenCalled()
  })

  it('400 con errors[] todos fuera de allowedFields → toast con detail, no setError', () => {
    const setError = vi.fn()
    const error = makeAxiosError(400, {
      code: 'COM-001',
      detail: 'Hay errores de validación',
      errors: [{ field: 'unknownField', message: 'algo' }],
    })
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'fallback',
      allowedFields: ['username', 'password'],
    })
    expect(setError).not.toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith('Hay errores de validación')
  })

  it('sin allowedFields → acepta cualquier field name en errors[]', () => {
    const setError = vi.fn()
    const error = makeAxiosError(400, {
      errors: [{ field: 'anything', message: 'msg' }],
    })
    handleApiFormError(error, { setError, fallbackMessage: 'fallback' })
    expect(setError).toHaveBeenCalledWith('anything', { type: 'backend', message: 'msg' })
  })

  it('codeFieldMap tiene prioridad sobre errors[] cuando ambos están presentes', () => {
    const setError = vi.fn()
    const error = makeAxiosError(400, {
      code: 'AUTH-004',
      detail: 'Contraseña incorrecta',
      errors: [{ field: 'newPassword', message: 'no debería usarse' }],
    })
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'fallback',
      codeFieldMap: { 'AUTH-004': 'currentPassword' },
      allowedFields: ['currentPassword', 'newPassword'],
    })
    expect(setError).toHaveBeenCalledTimes(1)
    expect(setError).toHaveBeenCalledWith('currentPassword', {
      type: 'backend',
      message: 'Contraseña incorrecta',
    })
  })

  it('si no hay match por código ni por errors[], pero hay detail → toast con detail', () => {
    const setError = vi.fn()
    const error = makeAxiosError(401, { detail: 'No autorizado' })
    handleApiFormError(error, { setError, fallbackMessage: 'fallback' })
    expect(setError).not.toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith('No autorizado')
  })

  it('si no hay body útil en el response → toast con fallbackMessage', () => {
    const setError = vi.fn()
    const error = makeAxiosError(500, undefined)
    handleApiFormError(error, {
      setError,
      fallbackMessage: 'No se pudo iniciar sesión. Verificá tu conexión e intentá de nuevo.',
    })
    expect(setError).not.toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith(
      'No se pudo iniciar sesión. Verificá tu conexión e intentá de nuevo.',
    )
  })
})
