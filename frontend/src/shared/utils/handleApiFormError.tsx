import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { TriangleAlert } from 'lucide-react'
import type { Problem } from '../../api'

interface HandleApiFormErrorOptions<F extends string> {
  /** setError de react-hook-form. */
  setError: (field: F, error: { type: string; message: string }) => void
  /** Mensaje cuando no se pudo extraer un error útil del backend (red caída, 5xx sin body, etc). */
  fallbackMessage: string
  /** Mapeo específico de Problem.code → field. Ej: { 'AUTH-004': 'currentPassword' } */
  codeFieldMap?: Record<string, F>
  /** Whitelist de fields que el form acepta en Problem.errors[]. Los que no estén se ignoran (con fallback a toast). */
  allowedFields?: readonly F[]
}

/**
 * Maneja errores de submit de un form contra un backend que devuelve Problem (RFC 7807).
 *
 * Flujo:
 * 1. Si no es AxiosError → toast genérico (red caída, error inesperado del cliente).
 * 2. Si `problem.code` matchea `codeFieldMap` → asigna `problem.detail` al field (independiente del status).
 * 3. Si `status === 400` y hay `problem.errors[]` → asigna cada error a su field (filtrando por `allowedFields`).
 * 4. Si hay `problem.detail` → toast con ese mensaje (cubre 401/403/409/etc).
 * 5. Fallback final → toast con `fallbackMessage`.
 */
export function handleApiFormError<F extends string>(
  error: unknown,
  options: HandleApiFormErrorOptions<F>,
): void {
  const { setError, fallbackMessage, codeFieldMap, allowedFields } = options

  if (!isAxiosError(error)) {
    toast.error('Error inesperado. Intenta de nuevo.', {
      icon: <TriangleAlert className="w-4 h-4" />,
    })
    return
  }

  const problem = error.response?.data as Problem | undefined
  const status = error.response?.status

  if (problem?.code && codeFieldMap?.[problem.code]) {
    setError(codeFieldMap[problem.code], {
      type: 'backend',
      message: problem.detail ?? 'Error de validación',
    })
    return
  }

  if (status === 400 && problem?.errors && problem.errors.length > 0) {
    let anyMatched = false
    for (const fieldError of problem.errors) {
      const field = fieldError.field as F
      if (!allowedFields || allowedFields.includes(field)) {
        setError(field, { type: 'backend', message: fieldError.message })
        anyMatched = true
      }
    }
    if (!anyMatched) {
      toast.error(problem.detail ?? 'Error de validación')
    }
    return
  }

  if (problem?.detail) {
    toast.error(problem.detail)
    return
  }

  toast.error(fallbackMessage)
}
