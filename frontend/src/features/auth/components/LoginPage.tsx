import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { LogIn } from 'lucide-react'
import { loginSchema, type LoginFormInput } from '../schemas/login.schema'
import { useLoginMutation } from '../hooks/useLoginMutation'
import { useAuth } from '../../../shared/auth/AuthContext'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { withMinDuration } from '../../../shared/utils/withMinDuration'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { cn } from '../../../shared/utils/cn'

const LOGIN_FIELDS: readonly (keyof LoginFormInput)[] = ['username', 'password']

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
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo iniciar sesión. Verificá tu conexión e intentá de nuevo.',
        allowedFields: LOGIN_FIELDS,
      })
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
            <TextField
              id="username"
              label="Usuario"
              autoComplete="username"
              placeholder="usuario"
              error={errors.username?.message}
              disabled={isPending}
              register={register('username')}
            />

            <TextField
              id="password"
              label="Contraseña"
              type="password"
              autoComplete="current-password"
              error={errors.password?.message}
              disabled={isPending}
              register={register('password')}
            />

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

