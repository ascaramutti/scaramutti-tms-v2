import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { FIELD_FOCUS_INVALID, fieldClasses } from '../../../../shared/ui/fieldClasses'
import { Modal } from '../../../../shared/ui/Modal'
import { Spinner } from '../../../../shared/ui/Spinner'
import { Textarea } from '../../../../shared/ui/Textarea'
import { cn } from '../../../../shared/utils/cn'
import { stripControlChars } from '../../../../shared/utils/sanitizeText'
import { useChangeServiceStatus } from '../../hooks/useChangeServiceStatus'
import { operationsKeys } from '../../queryKeys'
import type { ServiceWithEtag } from '../../hooks/useService'
import {
  SERVICE_PROGRESS_DATE_TIME_LABEL,
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  type ServiceProgressTransition,
} from '../../status/serviceStatusTransitions'
import {
  STATUS_NOTE_MAX_LENGTH,
  serviceProgressFormSchema,
  toServiceProgressRequest,
  type ServiceProgressFormValues,
} from '../../schemas/service-status.schema'
import { nowInLimaForInput } from '../../utils/limaDate'
import { ServiceStatusErrorAlert } from './ServiceStatusErrorAlert'
import { Button } from '../../../../shared/ui/Button'

interface ServiceProgressModalProps {
  isOpen: boolean
  onClose: () => void
  /** Iniciar o finalizar. Las dos piden lo mismo: cuándo pasó, y una nota. */
  transition: ServiceProgressTransition
  service: ServiceWithEtag
}

/**
 * Avance del viaje: iniciar o finalizar.
 *
 * Un solo componente para las dos porque piden exactamente lo mismo (la fecha y hora
 * real, más una nota opcional) y difieren solo en los textos y en qué marca escriben.
 * Dos archivos serían el mismo formulario copiado, y un arreglo en uno no llegaría al
 * otro.
 *
 * Se monta solo cuando está abierto, igual que el de asignar recursos, y acá pesa el
 * doble: react-hook-form congela sus valores iniciales al montar, y el valor inicial de
 * este formulario es la hora actual. Montado de entrada, el campo se precargaría con la
 * hora en que se abrió la pantalla y no con la de ahora.
 */
export function ServiceProgressModal(props: ServiceProgressModalProps) {
  if (!props.isOpen) return null
  return <ServiceProgressForm {...props} />
}

function ServiceProgressForm({ onClose, transition, service }: ServiceProgressModalProps) {
  const queryClient = useQueryClient()
  const changeStatus = useChangeServiceStatus(service.id)
  const presentation = SERVICE_STATUS_TRANSITION_PRESENTATION[transition]

  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ServiceProgressFormValues>({
    resolver: zodResolver(serviceProgressFormSchema),
    mode: 'onTouched',
    defaultValues: { dateTime: nowInLimaForInput(), note: '' },
  })

  // `useWatch` y no `watch`, por lo mismo que en el modal de asignación: `watch`
  // devuelve una función que el compilador de React no puede memoizar.
  const note = useWatch({ control, name: 'note' })

  // Solo el estado de la mutación: `isSubmitting` no llega a valer `true` porque el
  // envío usa `mutate`, que no devuelve promesa, así que el handler resuelve en el
  // mismo tick. Medido: sacarlo no mueve ninguna aserción.
  const isPending = changeStatus.isPending
  const dateTimeHelperId = 'service-progress-datetime-helper'
  const dateTimeErrorId = errors.dateTime ? 'service-progress-datetime-error' : undefined
  // El helper va SIEMPRE en la descripción y el error se suma cuando existe, igual que
  // hace el `Textarea` compartido. Acá pesa más que en otros campos: lo que ese texto
  // dice es en qué zona horaria se interpreta lo que se tipea, que es exactamente el
  // dato del que depende que el registro quede bien.
  const dateTimeDescribedBy = [dateTimeHelperId, dateTimeErrorId].filter(Boolean).join(' ')

  const onSubmit = handleSubmit((values) => {
    changeStatus.mutate(
      { ifMatch: service._etag, body: toServiceProgressRequest(values, transition) },
      {
        onSuccess: () => {
          toast.success(presentation.successMessage(service.code))
          onClose()
        },
        // El error NO cierra el modal: el usuario tiene que poder corregir la fecha o
        // reintentar sin volver a escribir lo que ya escribió.
      },
    )
  })

  return (
    <Modal isOpen onClose={onClose} title={presentation.modalTitle} size="md">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <div>
          <label
            htmlFor="service-progress-datetime"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            {SERVICE_PROGRESS_DATE_TIME_LABEL[transition]}
          </label>
          <input
            id="service-progress-datetime"
            type="datetime-local"
            // El paso al minuto, explícito. Es el default de este tipo de campo, así
            // que hoy no cambia nada: se escribe para que la precisión con la que este
            // formulario trabaja esté puesta acá y no dependa de un default ajeno. Lo
            // que de verdad protege la comparación es el recorte al minuto que hace
            // `isFutureInLima`.
            step={60}
            // El tope del selector acompaña a la validación en vez de reemplazarla. La
            // que decide es la de abajo; esta le evita al usuario elegir algo que el
            // formulario le va a rechazar.
            max={nowInLimaForInput()}
            disabled={isPending}
            aria-invalid={errors.dateTime ? true : undefined}
            aria-describedby={dateTimeDescribedBy}
            {...register('dateTime')}
            className={cn(
              'w-full',
              fieldClasses({ invalid: Boolean(errors.dateTime) }),
              errors.dateTime && FIELD_FOCUS_INVALID,
              // Mismas clases que le pone el `Textarea` compartido a su estado
              // deshabilitado: sin esto, con el pedido en vuelo un campo se apaga y el
              // de al lado conserva aspecto de editable.
              isPending && 'cursor-not-allowed bg-surface-subtle text-fg-muted',
            )}
          />
          <p id={dateTimeHelperId} className="mt-1.5 text-xs text-fg-muted">
            Hora de Perú. Viene puesta la de ahora; se puede corregir.
          </p>
          {errors.dateTime && (
            <p id={dateTimeErrorId} role="alert" className="mt-1.5 text-sm text-danger">
              {errors.dateTime.message}
            </p>
          )}
        </div>

        <Textarea
          id="service-progress-note"
          label="Nota (opcional)"
          rows={2}
          maxLength={STATUS_NOTE_MAX_LENGTH}
          showCounter
          value={note}
          helperText="Queda en la bitácora del viaje."
          error={errors.note?.message}
          register={register('note')}
          sanitize={stripControlChars}
          disabled={isPending}
        />

        {changeStatus.error && (
          <ServiceStatusErrorAlert
            error={changeStatus.error}
            fallback="No se pudo cambiar el estado del viaje. Intenta de nuevo."
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
          <Button variant="secondary" onClick={onClose}>
            Volver
          </Button>
          <Button variant="primary" type="submit" disabled={isPending}>
            {isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-on-solid" /> {presentation.pendingLabel}
              </>
            ) : (
              presentation.submitLabel
            )}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
