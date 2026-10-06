import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { WorkerDetailResponse } from '../../../api'
import { Alert } from '../../../shared/ui/Alert'
import { Button } from '../../../shared/ui/Button'
import { Modal } from '../../../shared/ui/Modal'
import { Spinner } from '../../../shared/ui/Spinner'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { useChangeWorkerStatus, type WorkerStatusAction } from '../hooks/useChangeWorkerStatus'
import { workerStatusErrorMessage } from '../workerApiErrors'

/** Como en el rechazo de una cotización: mientras envía, los dos botones se ven apagados. */
const DISABLED = 'disabled:cursor-not-allowed disabled:opacity-60'

interface WorkerStatusDialogProps {
  worker: WorkerDetailResponse
  onClose: () => void
  /** Un 404 al confirmar: lo resuelve la página. */
  onNotFound: () => void
}

/**
 * La confirmación de desactivar o reactivar, según el estado del trabajador. Dice lo que arrastra
 * la baja y lo que la reactivación no devuelve. Un error queda en el diálogo y la ficha no cambia;
 * un trabajador que no existe lo resuelve la página.
 */
export function WorkerStatusDialog({ worker, onClose, onNotFound }: WorkerStatusDialogProps) {
  // Fijada al abrir: al confirmar, la ficha se siembra con el estado nuevo antes de cerrar. Hoy el
  // cierre cae en el mismo render y ningún test lo distingue; fijarla no depende de ese orden.
  const [action] = useState<WorkerStatusAction>(worker.isActive ? 'deactivate' : 'reactivate')
  const changeStatus = useChangeWorkerStatus(worker.id)
  const [error, setError] = useState<string | null>(null)
  const confirmButton = useRef<HTMLButtonElement>(null)
  // Al enviar, el botón se deshabilita con el foco encima y el foco cae fuera del diálogo. Con el
  // error a la vista vuelve a confirmar, como en la salida de un viaje, para reintentar desde ahí.
  useEffect(() => {
    if (error && !changeStatus.isPending) confirmButton.current?.focus()
  }, [error, changeStatus.isPending])
  const name = `${worker.firstName} ${worker.lastName}`
  const copy = COPY[action]

  function confirm() {
    setError(null)
    changeStatus.mutate(action, {
      onSuccess: () => {
        toast.success(`${copy.done} a ${name}.`)
        onClose()
      },
      onError: (mutationError) => {
        if (isNotFoundError(mutationError)) {
          onNotFound()
          return
        }
        setError(workerStatusErrorMessage(mutationError))
      },
    })
  }

  return (
    // Mientras envía no se cierra: la respuesta tiene que verse en la ficha o en el diálogo.
    <Modal isOpen onClose={changeStatus.isPending ? () => {} : onClose} title={copy.title} size="sm">
      <div className="space-y-4">
        <p className="text-sm text-fg-body">
          {copy.question} a {name}?
        </p>
        {action === 'deactivate' ? <DeactivateNotes worker={worker} /> : <ReactivateNotes worker={worker} />}
        {error && (
          <Alert as="p" role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            {error}
          </Alert>
        )}
        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={onClose} disabled={changeStatus.isPending} className={DISABLED}>
            Cancelar
          </Button>
          <Button
            ref={confirmButton}
            variant={action === 'deactivate' ? 'danger' : 'success'}
            onClick={confirm}
            disabled={changeStatus.isPending}
            className={DISABLED}
          >
            {changeStatus.isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-on-solid" /> {copy.pending}
              </>
            ) : (
              copy.confirm
            )}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

const COPY = {
  deactivate: {
    title: 'Desactivar trabajador',
    question: '¿Desactivas',
    confirm: 'Desactivar',
    pending: 'Desactivando…',
    done: 'Se desactivó',
  },
  reactivate: {
    title: 'Reactivar trabajador',
    question: '¿Reactivas',
    confirm: 'Reactivar',
    pending: 'Reactivando…',
    done: 'Se reactivó',
  },
} as const

/** La baja apaga en cascada el usuario y la licencia activa; con licencia, avisa de los viajes. */
function DeactivateNotes({ worker }: { worker: WorkerDetailResponse }) {
  return (
    <>
      <DraggedByDeactivation hasUser={worker.hasUser} activeLicense={worker.driver?.isActive === true} />
      {worker.driver && (
        <p className="text-sm text-fg-body">
          Si tiene viajes pendientes asignados, quedarán marcados para reasignar en Operaciones.
        </p>
      )}
    </>
  )
}

/** La licencia con la palabra de la ficha ("Deshabilitada"); el usuario, con la del estado. */
function DraggedByDeactivation({ hasUser, activeLicense }: { hasUser: boolean; activeLicense: boolean }) {
  const text =
    hasUser && activeLicense
      ? 'Se desactiva también su usuario del sistema, y su licencia de conducir queda deshabilitada.'
      : hasUser
        ? 'Se desactiva también su usuario del sistema.'
        : activeLicense
          ? 'Su licencia de conducir queda deshabilitada.'
          : null
  return text && <p className="text-sm text-fg-body">{text}</p>
}

/**
 * La reactivación no toca la cuenta: se dice para que nadie espere que vuelva a entrar. La
 * licencia sí vuelve, con la misma condición que el backend: la tiene apagada y el cargo la lleva.
 */
function ReactivateNotes({ worker }: { worker: WorkerDetailResponse }) {
  const licenseComesBack = worker.driver?.isActive === false && worker.role.driverProfile !== 'NONE'
  return (
    <>
      {licenseComesBack && <p className="text-sm text-fg-body">Su licencia de conducir vuelve a quedar activa.</p>}
      {worker.hasUser && (
        <p className="text-sm text-fg-body">
          Su usuario sigue inactivo: reactivar al trabajador no le devuelve el acceso.
        </p>
      )}
    </>
  )
}
