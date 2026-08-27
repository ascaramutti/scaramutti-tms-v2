import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { Modal } from '../../../../shared/ui/Modal'
import { Spinner } from '../../../../shared/ui/Spinner'
import { Textarea } from '../../../../shared/ui/Textarea'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../../shared/ui/buttonStyles'
import { cn } from '../../../../shared/utils/cn'
import { stripControlChars } from '../../../../shared/utils/sanitizeText'
import { useChangeServiceStatus } from '../../hooks/useChangeServiceStatus'
import { operationsKeys } from '../../queryKeys'
import type { ServiceWithEtag } from '../../hooks/useService'
import {
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  type ServiceStatusTransition,
} from '../../status/serviceStatusTransitions'
import {
  STATUS_NOTE_MAX_LENGTH,
  serviceProgressFormSchema,
  toServiceProgressRequest,
  type ServiceProgressFormValues,
} from '../../schemas/service-status.schema'
import { nowInLimaForInput } from '../../utils/limaDate'
import { ServiceStatusErrorAlert } from './ServiceStatusErrorAlert'

interface ServiceProgressModalProps {
  isOpen: boolean
  onClose: () => void
  /** Iniciar o finalizar. Las dos piden lo mismo: cuándo pasó, y una nota. */
  transition: ServiceStatusTransition
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
    formState: { errors, isSubmitting },
  } = useForm<ServiceProgressFormValues>({
    resolver: zodResolver(serviceProgressFormSchema),
    mode: 'onTouched',
    defaultValues: { dateTime: nowInLimaForInput(), note: '' },
  })

  // `useWatch` y no `watch`, por lo mismo que en el modal de asignación: `watch`
  // devuelve una función que el compilador de React no puede memoizar.
  const note = useWatch({ control, name: 'note' })

  const isPending = isSubmitting || changeStatus.isPending
  const dateTimeErrorId = errors.dateTime ? 'service-progress-datetime-error' : undefined

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
    <Modal isOpen onClose={onClose} title={presentation.modalTitle} size="sm">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <div>
          <label
            htmlFor="service-progress-datetime"
            className="mb-1.5 block text-sm font-medium text-slate-700"
          >
            {presentation.dateTimeLabel}
          </label>
          <input
            id="service-progress-datetime"
            type="datetime-local"
            // Al minuto: sin esto hay navegadores que suman un selector de segundos y
            // devuelven un valor que el usuario no eligió.
            step={60}
            // El tope del selector acompaña a la validación en vez de reemplazarla. La
            // que decide es la de abajo; esta le evita al usuario elegir algo que el
            // formulario le va a rechazar.
            max={nowInLimaForInput()}
            aria-invalid={errors.dateTime ? true : undefined}
            aria-describedby={dateTimeErrorId}
            {...register('dateTime')}
            className={cn(
              'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:outline-none focus:ring-2',
              errors.dateTime
                ? 'border-red-300 focus:border-red-500 focus:ring-red-500'
                : 'border-slate-300 focus:border-blue-500 focus:ring-blue-500',
            )}
          />
          <p className="mt-1 text-xs text-slate-500">
            Hora de Perú. Viene puesta la de ahora; se puede corregir.
          </p>
          {errors.dateTime && (
            <p id={dateTimeErrorId} role="alert" className="mt-1 text-sm text-red-600">
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
            onRefresh={() =>
              void queryClient.invalidateQueries({
                queryKey: operationsKeys.serviceDetail(service.id),
              })
            }
          />
        )}

        <div className="flex justify-end gap-3 pt-2">
          <button type="button" onClick={onClose} className={SECONDARY_BUTTON}>
            Volver
          </button>
          <button type="submit" disabled={isPending} className={PRIMARY_BUTTON}>
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
