import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Navigate, useLocation } from 'react-router-dom'
import { LogIn } from 'lucide-react'
import { loginSchema, type LoginFormInput } from '../schemas/login.schema'
import { useLoginMutation } from '../hooks/useLoginMutation'
import { useAuth } from '../../../shared/auth/AuthContext'
import { canRoleOpenPath } from '../../../shared/auth/canRoleOpenPath'
import { landingPathFor } from '../../../shared/auth/roleLanding'
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

/**
 * Redirect post-autenticación según el rol del usuario. Todos los landings
 * viven en esta SPA, así que siempre navega el router.
 */
function AuthenticatedLanding({ from }: { from?: string }) {
  const { user } = useAuth()
  // El enlace directo gana, salvo que el rol no pueda abrirlo: ahí cae en su
  // principal, la misma decisión que toma el comodín del router. Sin esto, un
  // despachador que llega por un enlace a cotizaciones ve "Sin acceso".
  // `canRoleOpenPath` decide las dos cosas: si es una ruta de esta aplicación y
  // si ese rol la abre.
  const destino = from && canRoleOpenPath(from, user?.role) ? from : landingPathFor(user?.role)
  return <Navigate to={destino} replace />
}

export function LoginPage() {
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

  const from = (location.state as LocationState | null)?.from

  if (isAuthenticated) {
    return <AuthenticatedLanding from={from} />
  }

  const onSubmit = handleSubmit(async (values) => {
    try {
      const response = await withMinDuration(loginMutation.mutateAsync(values), MIN_LOADER_MS)
      setSession(response.token, response.refreshToken ?? null, response.user)
    } catch (error) {
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo iniciar sesión. Verifica tu conexión e intenta de nuevo.',
        allowedFields: LOGIN_FIELDS,
      })
    }
  })

  const isPending = loginMutation.isPending || isSubmitting

  return (
    <main className="min-h-screen flex items-center justify-center bg-surface-subtle px-4 py-8">
      <section className="w-full max-w-md">
        <div className="bg-surface rounded-2xl shadow-xl shadow-elevation ring-1 ring-border p-8">
          {/* Logo */}
          <div className="flex justify-center mb-6">
            <div className="bg-accent p-3 rounded-2xl shadow-md">
              <LogIn className="w-7 h-7 text-on-solid" aria-hidden="true" />
            </div>
          </div>

          {/* Headings — h1 describe la acción de la pantalla, no rebrandea Scaramutti TMS (eso ya está en el sidebar y title del browser) */}
          <div className="text-center mb-8">
            <h1 className="text-2xl font-semibold text-fg">Iniciar sesión</h1>
            <p className="text-sm text-fg-muted mt-1">Accedé al sistema con tus credenciales</p>
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
                'w-full inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium text-on-solid shadow-sm',
                'bg-accent hover:bg-accent-hover active:bg-accent-hover',
                'focus:outline-none focus:ring-2 focus:ring-focus focus:ring-offset-2 focus:ring-offset-surface',
                'disabled:bg-accent-disabled disabled:cursor-not-allowed',
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

        <p className="mt-6 text-center text-xs text-fg-subtle">
          Transportes Scaramutti S.A.C.
        </p>
      </section>
    </main>
  )
}

