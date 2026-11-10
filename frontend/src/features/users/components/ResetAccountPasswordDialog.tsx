import { useEffect, useRef, useState } from 'react'
import type { UserAccountDetailResponse } from '../../../api'
import { SESSION_WINDOW_NOTICE } from '../../../shared/auth/sessionNotice'
import { Button } from '../../../shared/ui/Button'
import { ConfirmActionDialog } from '../../../shared/ui/ConfirmActionDialog'
import { Modal } from '../../../shared/ui/Modal'
import { OneTimeSecret } from '../../../shared/ui/OneTimeSecret'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { useResetUserAccountPassword } from '../hooks/useResetUserAccountPassword'
import { resetPasswordErrorMessage } from '../userAccountApiErrors'

interface ResetAccountPasswordDialogProps {
  account: UserAccountDetailResponse
  onClose: () => void
  onNotFound: () => void
}

export function ResetAccountPasswordDialog({ account, onClose, onNotFound }: ResetAccountPasswordDialogProps) {
  const resetPassword = useResetUserAccountPassword(account.id)
  const [error, setError] = useState<string | null>(null)
  const [isReset, setIsReset] = useState(false)
  const [temporaryPassword, setTemporaryPassword] = useState<string | null>(null)
  const doneButton = useRef<HTMLButtonElement>(null)

  // Al borrarse la temporal, "Copiar" se va con el foco encima; sin Escape ni "Cerrar", el Tab
  // saldría del diálogo.
  useEffect(() => {
    if (isReset && temporaryPassword === null && document.activeElement === document.body) {
      doneButton.current?.focus()
    }
  }, [isReset, temporaryPassword])

  function confirm() {
    setError(null)
    resetPassword.mutate(undefined, {
      onSuccess: (response) => {
        setTemporaryPassword(response.temporaryPassword)
        // Sin observador, la caché descarta la mutación y, con ella, la respuesta que trae la temporal.
        resetPassword.reset()
        setIsReset(true)
      },
      onError: (mutationError) => {
        if (isNotFoundError(mutationError)) {
          onNotFound()
          return
        }
        setError(resetPasswordErrorMessage(mutationError))
      },
    })
  }

  const who = (
    <>
      <span className="font-mono">{account.username}</span> ({account.worker.fullName})
    </>
  )

  if (isReset) {
    return (
      <Modal isOpen onClose={onClose} title="Contraseña restablecida" size="md" dismissible={false}>
        <div className="space-y-4">
          <p className="text-sm text-fg-body">
            Esta es la contraseña temporal de {who}. Pídele que la cambie desde "Cambiar contraseña" al entrar.
          </p>
          <OneTimeSecret
            label="Contraseña temporal"
            value={temporaryPassword}
            onExpire={() => setTemporaryPassword(null)}
          />
          <div className="flex justify-end">
            <Button ref={doneButton} onClick={onClose}>
              Listo
            </Button>
          </div>
        </div>
      </Modal>
    )
  }

  return (
    <ConfirmActionDialog
      title="Restablecer contraseña"
      confirmLabel="Restablecer"
      pendingLabel="Restableciendo…"
      confirmVariant="primary"
      size="md"
      isPending={resetPassword.isPending}
      error={error}
      onConfirm={confirm}
      onClose={onClose}
    >
      <p className="text-sm text-fg-body">
        El sistema generará una contraseña temporal nueva para {who} y te la mostrará una sola vez. La actual deja de
        servir.
      </p>
      <p className="text-sm text-fg-body">{SESSION_WINDOW_NOTICE}</p>
      {!account.isActive && (
        <p className="text-sm text-fg-body">La cuenta sigue inactiva: para que pueda entrar, también hay que reactivarla.</p>
      )}
    </ConfirmActionDialog>
  )
}
