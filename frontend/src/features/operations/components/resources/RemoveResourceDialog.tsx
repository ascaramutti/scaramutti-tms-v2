import { useState } from 'react'
import { toast } from 'sonner'
import type { ServiceAdditionalResourceResponse } from '../../../../api'
import { Modal } from '../../../../shared/ui/Modal'
import { Spinner } from '../../../../shared/ui/Spinner'
import { formatDateTime } from '../../../../shared/utils/formatters'
import { getApiErrorMessage } from '../../../../shared/utils/getApiErrorMessage'
import { useRemoveServiceResource } from '../../hooks/useRemoveServiceResource'
import { describeAdditionalResource } from '../../status/resourcePresentation'
import { Button } from '../../../../shared/ui/Button'

interface RemoveResourceDialogProps {
  isOpen: boolean
  onClose: () => void
  serviceId: number
  serviceCode: string
  resource: ServiceAdditionalResourceResponse
}

/**
 * Confirmación de la baja de un refuerzo.
 *
 * Pide confirmación porque la baja es física y no tiene deshacer: la fila desaparece
 * y el endpoint no lleva cuerpo, así que no hay ningún paso intermedio que frene un
 * clic errado en una lista de filas parecidas. El diálogo repite QUÉ refuerzo se
 * quita, que es la información que evita el error.
 *
 * No pide motivo, y eso lo dice el contrato: el porqué de un refuerzo cargado por
 * error es siempre el mismo, y el quién, el cuándo y el qué ya quedan en el rastro.
 */
export function RemoveResourceDialog(props: RemoveResourceDialogProps) {
  if (!props.isOpen) return null
  return <RemoveResourceConfirm {...props} />
}

function RemoveResourceConfirm({
  onClose,
  serviceId,
  serviceCode,
  resource,
}: RemoveResourceDialogProps) {
  const removeResource = useRemoveServiceResource(serviceId)
  const [error, setError] = useState<string | null>(null)

  function confirm() {
    setError(null)
    removeResource.mutate(resource.id, {
      onSuccess: () => {
        toast.success(`Refuerzo quitado de ${serviceCode}.`)
        onClose()
      },
      onError: (mutationError) => {
        setError(
          getApiErrorMessage(mutationError, 'No se pudo quitar el refuerzo. Intenta de nuevo.'),
        )
      },
    })
  }

  return (
    <Modal isOpen onClose={onClose} title="Quitar refuerzo" size="sm">
      <div className="space-y-4">
        <div className="rounded-lg bg-slate-50 px-4 py-3">
          <p className="text-sm font-medium text-slate-900">
            {describeAdditionalResource(resource)}
          </p>
          <p className="mt-0.5 text-sm text-slate-700">{resource.reason}</p>
          <p className="mt-0.5 text-xs text-slate-500">
            {resource.assignedBy.fullName} · {formatDateTime(resource.assignedAt)}
          </p>
        </div>

        <p className="text-sm text-slate-700">
          Se quita del viaje de forma permanente. El motivo y quién lo cargó quedan en la
          bitácora.
        </p>

        {error && (
          <p
            role="alert"
            className="rounded-lg border border-red-200 bg-red-50 px-4 py-2.5 text-sm text-red-700"
          >
            {error}
          </p>
        )}

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button
            variant="danger"
            onClick={confirm}
            disabled={removeResource.isPending}
          >
            {removeResource.isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-white" /> Quitando…
              </>
            ) : (
              'Quitar refuerzo'
            )}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
