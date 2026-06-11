import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { KeyRound } from 'lucide-react'
import {
  changePasswordSchema,
  type ChangePasswordFormInput,
} from '../schemas/change-password.schema'
import { useChangePasswordMutation } from '../hooks/useChangePasswordMutation'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { withMinDuration } from '../../../shared/utils/withMinDuration'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { cn } from '../../../shared/utils/cn'

// Tiempo mínimo del spinner — consistente con LoginPage.
const MIN_LOADER_MS = import.meta.env.MODE === 'test' ? 0 : 1000

type FieldName = keyof ChangePasswordFormInput

const CHANGE_PASSWORD_FIELDS: readonly FieldName[] = ['currentPassword', 'newPassword']

export function ChangePasswordPage() {
  const navigate = useNavigate()
  const mutation = useChangePasswordMutation()

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormInput>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })

  useEffect(() => {
    setFocus('currentPassword')
  }, [setFocus])

  const onSubmit = handleSubmit(async (values) => {
    try {
      await withMinDuration(
        mutation.mutateAsync({
          currentPassword: values.currentPassword,
          newPassword: values.newPassword,
        }),
        MIN_LOADER_MS,
      )
      toast.success('Contraseña actualizada')
      navigate('/', { replace: true })
    } catch (error) {
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo cambiar la contraseña. Verifica tu conexión e intenta de nuevo.',
        allowedFields: CHANGE_PASSWORD_FIELDS,
        codeFieldMap: { 'AUTH-004': 'currentPassword' },
      })
    }
  })

  const isPending = mutation.isPending || isSubmitting

  return (
    <div className="mx-auto max-w-xl px-6 py-12">
      <div className="bg-white rounded-2xl ring-1 ring-slate-200 p-8">
        {/* Header */}
        <div className="flex justify-center mb-6">
          <div className="bg-blue-600 p-3 rounded-2xl shadow-md">
            <KeyRound className="w-7 h-7 text-white" aria-hidden="true" />
          </div>
        </div>

        <div className="text-center mb-8">
          <h1 className="text-2xl font-semibold text-slate-900">Cambiar contraseña</h1>
          <p className="text-sm text-slate-500 mt-1">
            Ingresa tu contraseña actual y la nueva
          </p>
        </div>

        <form onSubmit={onSubmit} noValidate aria-busy={isPending} className="space-y-5">
          <TextField
            id="currentPassword"
            label="Contraseña actual"
            type="password"
            autoComplete="current-password"
            error={errors.currentPassword?.message}
            disabled={isPending}
            register={register('currentPassword')}
          />
          <TextField
            id="newPassword"
            label="Nueva contraseña"
            type="password"
            autoComplete="new-password"
            error={errors.newPassword?.message}
            disabled={isPending}
            register={register('newPassword')}
          />
          <TextField
            id="confirmPassword"
            label="Confirmar nueva contraseña"
            type="password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
            disabled={isPending}
            register={register('confirmPassword')}
          />

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={() => navigate('/')}
              disabled={isPending}
              className="px-4 py-2.5 text-sm font-medium text-slate-700 rounded-lg hover:bg-slate-100 focus:outline-none focus:ring-2 focus:ring-slate-400 focus:ring-offset-2 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={isPending}
              className={cn(
                'inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium text-white shadow-sm',
                'bg-blue-600 hover:bg-blue-700 active:bg-blue-800',
                'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2',
                'disabled:bg-blue-400 disabled:cursor-not-allowed',
                'transition-colors',
              )}
            >
              {isPending ? (
                <>
                  <Spinner size={16} label="Guardando" />
                  Guardando…
                </>
              ) : (
                'Cambiar contraseña'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

