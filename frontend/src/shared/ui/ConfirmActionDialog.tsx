import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { Alert } from './Alert'
import { Button } from './Button'
import type { ButtonVariant } from './buttonClasses'
import { Modal } from './Modal'
import { Spinner } from './Spinner'

const DISABLED = 'disabled:cursor-not-allowed disabled:opacity-60'

interface ConfirmActionDialogProps {
  title: string
  children: ReactNode
  confirmLabel: string
  pendingLabel: string
  confirmVariant: Exclude<ButtonVariant, 'secondary'>
  size?: 'sm' | 'md'
  isPending: boolean
  error: string | null
  onConfirm: () => void
  onClose: () => void
}

export function ConfirmActionDialog({
  title,
  children,
  confirmLabel,
  pendingLabel,
  confirmVariant,
  size = 'sm',
  isPending,
  error,
  onConfirm,
  onClose,
}: ConfirmActionDialogProps) {
  const confirmButton = useRef<HTMLButtonElement>(null)
  // Al enviar, el botón se deshabilita con el foco encima y el foco cae fuera del diálogo. Con el
  // error a la vista vuelve a confirmar, para reintentar desde ahí.
  useEffect(() => {
    if (error && !isPending) confirmButton.current?.focus()
  }, [error, isPending])

  return (
    // Mientras envía no se cierra: la respuesta tiene que verse en la pantalla o en el diálogo.
    <Modal isOpen onClose={isPending ? () => {} : onClose} title={title} size={size}>
      <div className="space-y-4">
        {children}
        {error && (
          <Alert as="p" role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            {error}
          </Alert>
        )}
        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={onClose} disabled={isPending} className={DISABLED}>
            Cancelar
          </Button>
          <Button
            ref={confirmButton}
            variant={confirmVariant}
            onClick={onConfirm}
            disabled={isPending}
            className={DISABLED}
          >
            {isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-on-solid" /> {pendingLabel}
              </>
            ) : (
              confirmLabel
            )}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
