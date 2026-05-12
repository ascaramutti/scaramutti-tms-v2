import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { loginSchema, type LoginFormInput } from '../schemas/login.schema'
import { useLoginMutation } from '../hooks/useLoginMutation'
import { useAuth } from '../../../shared/auth/AuthContext'
import { Spinner } from '../../../shared/ui/Spinner'
import { withMinDuration } from '../../../shared/utils/withMinDuration'
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

  // Si ya esta autenticado y entra a /login, redirigir al home.
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
    <main style={pageStyle}>
      <section style={cardStyle}>
        <h1 style={{ margin: '0 0 0.5rem' }}>Scaramutti TMS</h1>
        <p style={{ margin: '0 0 1.5rem', color: '#666' }}>Iniciar sesión</p>

        <form onSubmit={onSubmit} noValidate aria-busy={isPending}>
          <div style={fieldStyle}>
            <label htmlFor="username">Usuario</label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              aria-invalid={!!errors.username}
              aria-describedby={errors.username ? 'username-error' : undefined}
              disabled={isPending}
              {...register('username')}
            />
            {errors.username && (
              <span id="username-error" role="alert" style={errorStyle}>
                {errors.username.message}
              </span>
            )}
          </div>

          <div style={fieldStyle}>
            <label htmlFor="password">Contraseña</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              aria-invalid={!!errors.password}
              aria-describedby={errors.password ? 'password-error' : undefined}
              disabled={isPending}
              {...register('password')}
            />
            {errors.password && (
              <span id="password-error" role="alert" style={errorStyle}>
                {errors.password.message}
              </span>
            )}
          </div>

          <button type="submit" disabled={isPending} style={buttonStyle}>
            {isPending ? (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem' }}>
                <Spinner size={16} label="Ingresando" />
                Ingresando…
              </span>
            ) : (
              'Iniciar sesión'
            )}
          </button>
        </form>
      </section>
    </main>
  )
}

function handleLoginError(
  error: unknown,
  setError: (field: keyof LoginFormInput, error: { type: string; message: string }) => void,
): void {
  if (!isAxiosError(error)) {
    toast.error('Error inesperado. Intentá de nuevo.')
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

// Estilos inline temporales — se reemplazan cuando armemos UI lib / Tailwind.
const pageStyle: React.CSSProperties = {
  minHeight: '100vh',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: '1rem',
  background: '#f5f5f5',
}

const cardStyle: React.CSSProperties = {
  width: '100%',
  maxWidth: '24rem',
  padding: '2rem',
  background: 'white',
  borderRadius: '0.5rem',
  boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
}

const fieldStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '0.25rem',
  marginBottom: '1rem',
}

const errorStyle: React.CSSProperties = {
  color: '#c33',
  fontSize: '0.875rem',
}

const buttonStyle: React.CSSProperties = {
  width: '100%',
  padding: '0.75rem',
  fontSize: '1rem',
  cursor: 'pointer',
  border: 'none',
  borderRadius: '0.25rem',
  background: '#222',
  color: 'white',
}
