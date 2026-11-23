import { useState } from 'react'
import { toast } from 'sonner'
import type { UserAccountDetailResponse } from '../../../api'
import { SESSION_WINDOW_NOTICE } from '../../../shared/auth/sessionNotice'
import { ConfirmActionDialog } from '../../../shared/ui/ConfirmActionDialog'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { useChangeUserAccountStatus, type AccountStatusAction } from '../hooks/useChangeUserAccountStatus'
import { accountStatusErrorMessage } from '../userAccountApiErrors'

interface AccountStatusDialogProps {
  account: UserAccountDetailResponse
  onClose: () => void
  onNotFound: () => void
}

export function AccountStatusDialog({ account, onClose, onNotFound }: AccountStatusDialogProps) {
  // Fijada al abrir: al confirmar, el detalle se siembra con el estado nuevo antes de cerrar.
  const [action] = useState<AccountStatusAction>(account.isActive ? 'deactivate' : 'reactivate')
  const changeStatus = useChangeUserAccountStatus(account.id)
  const [error, setError] = useState<string | null>(null)
  const copy = COPY[action]

  function confirm() {
    setError(null)
    changeStatus.mutate(action, {
      onSuccess: () => {
        toast.success(`${copy.done} la cuenta ${account.username}.`)
        onClose()
      },
      onError: (mutationError) => {
        if (isNotFoundError(mutationError)) {
          onNotFound()
          return
        }
        setError(accountStatusErrorMessage(mutationError))
      },
    })
  }

  return (
    <ConfirmActionDialog
      title={copy.title}
      confirmLabel={copy.confirm}
      pendingLabel={copy.pending}
      confirmVariant={action === 'deactivate' ? 'danger' : 'success'}
      isPending={changeStatus.isPending}
      error={error}
      onConfirm={confirm}
      onClose={onClose}
    >
      <p className="text-sm text-fg-body">
        {copy.question} la cuenta <span className="font-mono">{account.username}</span> de {account.worker.fullName}?
      </p>
      {action === 'deactivate' ? (
        <>
          <p className="text-sm text-fg-body">
            Ya no podrá iniciar sesión.{account.worker.isActive && ' Su trabajador sigue activo.'}
          </p>
          <p className="text-sm text-fg-body">{SESSION_WINDOW_NOTICE}</p>
        </>
      ) : (
        <>
          <p className="text-sm text-fg-body">Podrá volver a iniciar sesión con su contraseña actual.</p>
          <p className="text-sm text-fg-body">
            Si tenía una sesión abierta antes de la baja, no se renueva: tendrá que ingresar de nuevo.
          </p>
        </>
      )}
    </ConfirmActionDialog>
  )
}

const COPY = {
  deactivate: {
    title: 'Desactivar cuenta',
    question: '¿Desactivas',
    confirm: 'Desactivar',
    pending: 'Desactivando…',
    done: 'Se desactivó',
  },
  reactivate: {
    title: 'Reactivar cuenta',
    question: '¿Reactivas',
    confirm: 'Reactivar',
    pending: 'Reactivando…',
    done: 'Se reactivó',
  },
} as const
