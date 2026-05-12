import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { LogIn, TriangleAlert } from 'lucide-react'
import { loginSchema, type LoginFormInput } from '../schemas/login.schema'
import { useLoginMutation } from '../hooks/useLoginMutation'
import { useAuth } from '../../../shared/auth/AuthContext'
import { Spinner } from '../../../shared/ui/Spinner'
import { withMinDuration } from '../../../shared/utils/withMinDuration'
import { cn } from '../../../shared/utils/cn'
import type { Problem } from '../../../api'

// Tiempo minimo que se muestra el loader del submit. En tests usamos 0 para
// no ralentizar la suite (cada test del submit tardaria 1s+ extra).
const MIN_LOADER_MS = import.meta.env.MODE === 'test' ? 0 : 1000

interface LocationState {
  from?: string
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { isAuthenticated, setSession } = useAuth()
  const loginMutation = useLoginMutation()

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  })

  useEffect(() => {
    setFocus('username')
  }, [setFocus])

  if (isAuthenticated) {
    return <Navigate to="/" replace />
  }

  const onSubmit = handleSubmit(async (values) => {
    try {
      const response = await withMinDuration(loginMutation.mutateAsync(values), MIN_LOADER_MS)
      setSession(response.token, response.refreshToken ?? null, response.user)
      const from = (location.state as LocationState | null)?.from ?? '/'
      navigate(from, { replace: true })
    } catch (error) {
      handleLoginError(error, setError)
    }
  })

  const isPending = loginMutation.isPending || isSubmitting

  return (
    <main className="min-h-screen flex items-center justify-center bg-slate-50 px-4 py-8">
      <section className="w-full max-w-md">
        <div className="bg-white rounded-2xl shadow-xl shadow-slate-200 ring-1 ring-slate-200 p-8">
          {/* Logo */}
          <div className="flex justify-center mb-6">
            <div className="bg-blue-600 p-3 rounded-2xl shadow-md">
              <LogIn className="w-7 h-7 text-white" aria-hidden="true" />
            </div>
          </div>

          {/* Headings — h1 describe la acción de la pantalla, no rebrandea Scaramutti TMS (eso ya está en el HomePage header y title del browser) */}
          <div className="text-center mb-8">
            <h1 className="text-2xl font-semibold text-slate-900">Iniciar sesión</h1>
            <p className="text-sm text-slate-500 mt-1">Accedé al sistema con tus credenciales</p>
          </div>

          <form onSubmit={onSubmit} noValidate aria-busy={isPending} className="space-y-5">
            {/* Username */}
            <div>
              <label htmlFor="username" className="block text-sm font-medium text-slate-700 mb-1.5">
                Usuario
              </label>
              <input
                id="username"
                type="text"
                autoComplete="username"
                aria-invalid={!!errors.username}
                aria-describedby={errors.username ? 'username-error' : undefined}
                disabled={isPending}
                placeholder="usuario"
                className={cn(
                  'w-full rounded-lg border bg-white px-3.5 py-2.5 text-slate-900 placeholder:text-slate-400',
                  'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500',
                  'disabled:bg-slate-50 disabled:text-slate-400 disabled:cursor-not-allowed',
                  errors.username
                    ? 'border-red-300 focus:ring-red-500 focus:border-red-500'
                    : 'border-slate-300',
                )}
                {...register('username')}
              />
              {errors.username && (
                <p id="username-error" role="alert" className="mt-1.5 text-sm text-red-600">
                  {errors.username.message}
                </p>
              )}
            </div>

            {/* Password */}
            <div>
              <label htmlFor="password" className="block text-sm font-medium text-slate-700 mb-1.5">
                Contraseña
              </label>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                aria-invalid={!!errors.password}
                aria-describedby={errors.password ? 'password-error' : undefined}
                disabled={isPending}
                placeholder="••••••••"
                className={cn(
                  'w-full rounded-lg border bg-white px-3.5 py-2.5 text-slate-900 placeholder:text-slate-400',
                  'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500',
                  'disabled:bg-slate-50 disabled:text-slate-400 disabled:cursor-not-allowed',
                  errors.password
                    ? 'border-red-300 focus:ring-red-500 focus:border-red-500'
                    : 'border-slate-300',
                )}
                {...register('password')}
              />
              {errors.password && (
                <p id="password-error" role="alert" className="mt-1.5 text-sm text-red-600">
                  {errors.password.message}
                </p>
              )}
            </div>

            {/* Submit */}
            <button
              type="submit"
              disabled={isPending}
              className={cn(
                'w-full inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium text-white shadow-sm',
                'bg-blue-600 hover:bg-blue-700 active:bg-blue-800',
                'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2',
                'disabled:bg-blue-400 disabled:cursor-not-allowed',
                'transition-colors',
              )}
            >
              {isPending ? (
                <>
                  <Spinner size={16} label="Ingresando" />
                  Ingresando…
                </>
              ) : (
                'Iniciar sesión'
              )}
            </button>
          </form>
        </div>

        <p className="mt-6 text-center text-xs text-slate-400">
          Transportes Scaramutti S.A.C.
        </p>
      </section>
    </main>
  )
}

function handleLoginError(
  error: unknown,
  setError: (field: keyof LoginFormInput, error: { type: string; message: string }) => void,
): void {
  if (!isAxiosError(error)) {
    toast.error('Error inesperado. Intentá de nuevo.', { icon: <TriangleAlert className="w-4 h-4" /> })
    return
  }

  const problem = error.response?.data as Problem | undefined
  const status = error.response?.status

  // 400 con errores por campo → asignar a los inputs correspondientes.
  if (status === 400 && problem?.errors && problem.errors.length > 0) {
    let anyMatched = false
    for (const fieldError of problem.errors) {
      if (fieldError.field === 'username' || fieldError.field === 'password') {
        setError(fieldError.field, { type: 'backend', message: fieldError.message })
        anyMatched = true
      }
    }
    if (!anyMatched) {
      toast.error(problem.detail ?? 'Error de validación')
    }
    return
  }

  // 401, 403, etc → mostrar el detail del Problem.
  if (problem?.detail) {
    toast.error(problem.detail)
    return
  }

  toast.error('No se pudo iniciar sesión. Verificá tu conexión e intentá de nuevo.')
}
