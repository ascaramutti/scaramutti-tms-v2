import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { Modal } from '../../../../shared/ui/Modal'
import { Spinner } from '../../../../shared/ui/Spinner'
import { Textarea } from '../../../../shared/ui/Textarea'
import { DANGER_BUTTON, SECONDARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import { stripControlChars } from '../../../../shared/utils/sanitizeText'
import { useChangeServiceStatus } from '../../hooks/useChangeServiceStatus'
import { operationsKeys } from '../../queryKeys'
import type { ServiceWithEtag } from '../../hooks/useService'
import { SERVICE_STATUS_TRANSITION_PRESENTATION } from '../../status/serviceStatusTransitions'
import {
  CANCEL_REASON_MIN_LENGTH,
  STATUS_NOTE_MAX_LENGTH,
  cancelServiceFormSchema,
  toCancelServiceRequest,
  type CancelServiceFormValues,
} from '../../schemas/service-status.schema'
import { ServiceStatusErrorAlert } from './ServiceStatusErrorAlert'

interface CancelServiceModalProps {
  isOpen: boolean
  onClose: () => void
  service: ServiceWithEtag
}

/**
 * Cancelación del viaje.
 *
 * Tiene componente propio y no es una rama del formulario que inicia y finaliza: difiere
 * en todo a la vez. No lleva fecha (mandarla es un rechazo), el texto libre pasa de nota
 * opcional a motivo obligatorio, el botón que confirma es destructivo, y el despacho no
 * la ve. Compartir el cuerpo con aquel daría un componente que es casi todo condicional.
 *
 * Se monta solo cuando está abierto: el formulario congela sus valores al montar, y
 * reabrirlo tiene que empezar en blanco y no con el motivo que se escribió la vez pasada.
 */
export function CancelServiceModal(props: CancelServiceModalProps) {
  if (!props.isOpen) return null
  return <CancelServiceForm {...props} />
}

function CancelServiceForm({ onClose, service }: CancelServiceModalProps) {
  const queryClient = useQueryClient()
  const changeStatus = useChangeServiceStatus(service.id)
  const presentation = SERVICE_STATUS_TRANSITION_PRESENTATION.CANCELLED

  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CancelServiceFormValues>({
    resolver: zodResolver(cancelServiceFormSchema),
    mode: 'onTouched',
    defaultValues: { note: '' },
  })

  const note = useWatch({ control, name: 'note' })
  // Solo el estado de la mutación: `isSubmitting` no llega a valer `true` porque el
  // envío usa `mutate`, que no devuelve promesa, así que el handler resuelve en el
  // mismo tick. Medido: sacarlo no mueve ninguna aserción.
  const isPending = changeStatus.isPending

  const onSubmit = handleSubmit((values) => {
    changeStatus.mutate(
      { ifMatch: service._etag, body: toCancelServiceRequest(values) },
      {
        onSuccess: () => {
          toast.success(presentation.successMessage(service.code))
          onClose()
        },
      },
    )
  })

  return (
    <Modal isOpen onClose={onClose} title={presentation.modalTitle} size="md">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {/* Se dice que el estado es terminal y no que "no se puede revertir": la
            reapertura existe en el contrato y llega en su propio cambio, así que
            escribir hoy que no se revierte sería dejar puesta una frase que va a
            volverse falsa. */}
        <p className="text-sm text-slate-600">
          Se cancela el viaje <span className="font-medium">{service.code}</span> de{' '}
          {service.client.name}. Pasa a <span className="font-medium">Cancelado</span>, que
          es un estado terminal.
        </p>

        <Textarea
          id="cancel-service-reason"
          label="Motivo de la cancelación"
          rows={3}
          maxLength={STATUS_NOTE_MAX_LENGTH}
          showCounter
          value={note}
          helperText={`Mínimo ${CANCEL_REASON_MIN_LENGTH} caracteres. Queda en la bitácora del viaje.`}
          error={errors.note?.message}
          register={register('note')}
          sanitize={stripControlChars}
          disabled={isPending}
        />

        {changeStatus.error && (
          <ServiceStatusErrorAlert
            error={changeStatus.error}
            fallback="No se pudo cancelar el viaje. Intenta de nuevo."
            onRefresh={() => {
              void queryClient.invalidateQueries({
                queryKey: operationsKeys.serviceDetail(service.id),
              })
              // Y se cierra. El 412 dice que lo que el usuario vio ya no es lo actual,
              // así que la respuesta es volver a mirar: dejar el formulario abierto
              // sobre datos que acaban de cambiar invita a reenviarlo a ciegas. Sin
              // esto, además, apretar el botón no producía ningún cambio visible.
              onClose()
            }}
          />
        )}

        <div className="flex justify-end gap-3 pt-2">
          {/* "Volver" y no "Cancelar": es la única pantalla del sistema donde esa
              palabra nombra las dos cosas a la vez. */}
          <button type="button" onClick={onClose} className={SECONDARY_BUTTON}>
            Volver
          </button>
          <button type="submit" disabled={isPending} className={DANGER_BUTTON}>
            {isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-white" /> {presentation.pendingLabel}
              </>
            ) : (
              presentation.submitLabel
            )}
          </button>
        </div>
      </form>
    </Modal>
  )
}
