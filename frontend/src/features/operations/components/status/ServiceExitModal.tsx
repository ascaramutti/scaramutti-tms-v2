import { useEffect, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { Modal } from '../../../../shared/ui/Modal'
import { Spinner } from '../../../../shared/ui/Spinner'
import { Textarea } from '../../../../shared/ui/Textarea'
import { stripControlChars } from '../../../../shared/utils/sanitizeText'
import { useChangeServiceStatus } from '../../hooks/useChangeServiceStatus'
import { operationsKeys } from '../../queryKeys'
import type { ServiceWithEtag } from '../../hooks/useService'
import type { UserRole } from '../../../../api'
import {
  REOPEN_AVAILABLE_NOTE,
  REOPEN_FORCE_WARNING,
  SERVICE_EXIT_FAILURE_MESSAGE,
  SERVICE_EXIT_REASON_LABEL,
  SERVICE_EXIT_TRANSITION_PROMPT,
  SERVICE_STATUS_TRANSITION_PRESENTATION,
  availableServiceStatusTransitions,
  serviceKeepsReopenPath,
  type ServiceExitTransition,
} from '../../status/serviceStatusTransitions'
import {
  SERVICE_EXIT_REASON_MIN_LENGTH,
  STATUS_NOTE_MAX_LENGTH,
  serviceExitFormSchema,
  toServiceExitRequest,
  type ServiceExitFormValues,
} from '../../schemas/service-status.schema'
import {
  getServiceOperationError,
  type ServiceOperationError,
} from '../../utils/serviceResourceConflict'
import { ResourceConflictAlert } from '../resources/ResourceConflictAlert'
import { ServiceStatusErrorAlert } from './ServiceStatusErrorAlert'
import { Button } from '../../../../shared/ui/Button'

interface ServiceExitModalProps {
  isOpen: boolean
  onClose: () => void
  /** Cancelar, eliminar o reabrir. Las tres piden lo mismo: la versión y un motivo. */
  transition: ServiceExitTransition
  service: ServiceWithEtag
  /** Quién está mirando. Decide si se le dice que hay vuelta atrás: la jefatura de
   * operaciones saca el viaje del circuito pero está vetada de reabrirlo. */
  role: UserRole | undefined
}

/**
 * Las tres transiciones que sacan el viaje del circuito o lo devuelven: cancelar,
 * eliminar y reabrir.
 *
 * Un solo componente porque piden exactamente lo mismo (la versión del recurso y un
 * motivo obligatorio) y ninguna fecha el viaje. Lo que cambia entre ellas es el texto y
 * quién puede pedirlas, y eso son parámetros, no ramas.
 *
 * No comparte cuerpo con el formulario que inicia y finaliza, que sí difiere en todo a la
 * vez: aquel lleva fecha, su texto es una nota opcional y su botón no es destructivo.
 *
 * Se monta solo cuando está abierto: el formulario congela sus valores al montar, y
 * volver a abrirlo tiene que empezar en blanco y no con el motivo de la vez pasada.
 */
export function ServiceExitModal(props: ServiceExitModalProps) {
  if (!props.isOpen) return null
  return <ServiceExitForm {...props} />
}

function ServiceExitForm({ onClose, transition, service, role }: ServiceExitModalProps) {
  const queryClient = useQueryClient()
  const changeStatus = useChangeServiceStatus(service.id)
  const presentation = SERVICE_STATUS_TRANSITION_PRESENTATION[transition]

  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ServiceExitFormValues>({
    resolver: zodResolver(serviceExitFormSchema),
    mode: 'onTouched',
    defaultValues: { note: '' },
  })

  const note = useWatch({ control, name: 'note' })
  /*
   * El conflicto de recursos solo aparece al REABRIR, y es el efecto de dos decisiones
   * que por separado están bien: cancelar no libera los recursos (para no perder quién
   * estaba asignado) pero un viaje fuera del circuito deja de retenerlos, así que en el
   * medio otro viaje se los puede llevar. Devolverlo a un estado que los retiene los
   * pondría a compartirlos.
   *
   * Se guarda aparte del error genérico porque tiene su propio aviso, con la tabla de
   * qué recurso choca y en qué viaje, y su propio botón.
   */
  const [conflict, setConflict] = useState<ServiceOperationError | null>(null)
  const submitButton = useRef<HTMLButtonElement>(null)
  /*
   * Forzar se aprieta desde un botón que vive DENTRO del aviso de conflicto, así que al
   * desaparecer el aviso el foco cae en `body`: fuera del diálogo, donde el siguiente
   * tabulador recorre la pantalla de atrás. Se recupera acá y no en el envío porque el
   * aviso puede volver (otro conflicto) y entonces no hubo nada que recuperar.
   */
  const hadConflict = useRef(false)
  useEffect(() => {
    if (conflict !== null) {
      hadConflict.current = true
      return
    }
    if (hadConflict.current) {
      hadConflict.current = false
      submitButton.current?.focus()
    }
  }, [conflict])
  // La vuelta atrás se menciona solo a quien la tiene, y sale de la misma tabla que la
  // decide: una frase fija le prometía a la jefatura de operaciones algo que su rol le
  // niega, y en el viaje ya cancelado tampoco iba a ver el botón.
  // Se pregunta por el estado en que el viaje va a QUEDAR, que para estas dos salidas se
  // llama igual que la transición, y por lo que la pantalla ya sabe del viaje.
  const showsReopenNote =
    transition !== 'REOPENED' &&
    serviceKeepsReopenPath(service) &&
    availableServiceStatusTransitions(transition, role).includes('REOPENED')
  // Solo el estado de la mutación: `isSubmitting` no llega a valer `true` porque el
  // envío usa `mutate`, que no devuelve promesa, así que el handler resuelve en el
  // mismo tick. Medido: sacarlo no mueve ninguna aserción.
  const isPending = changeStatus.isPending

  /*
   * `force` es argumento y no un campo del formulario, igual que en la asignación de
   * recursos: guardado en el form quedaría pegado en `true` después del primer forzado,
   * y un intento posterior viajaría forzado sin que nadie lo pidiera.
   */
  function submit(values: ServiceExitFormValues, force: boolean) {
    setConflict(null)
    changeStatus.mutate(
      { ifMatch: service._etag, body: toServiceExitRequest(values, transition, force) },
      {
        onSuccess: () => {
          toast.success(presentation.successMessage(service.code))
          onClose()
        },
        onError: (error) => {
          const operation = getServiceOperationError(error)
          // Solo el conflicto forzable arma la tabla; el resto de los errores caen en
          // el aviso genérico, que muestra el `detail` del servidor.
          setConflict(operation?.conflicts.length ? operation : null)
        },
      },
    )
  }

  const onSubmit = handleSubmit((values) => submit(values, false))

  return (
    <Modal isOpen onClose={onClose} title={presentation.modalTitle} size="md">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {/* Se nombra el viaje y el cliente: el código vive en el encabezado, detrás del
            fondo del diálogo, y esto es lo que evita actuar sobre el viaje equivocado.
            Y no se dice "terminal": desde que reabrir existe, esa palabra significaría
            para el lector algo que dejó de ser cierto. */}
        <p className="text-sm text-fg-body">
          Viaje <span className="font-medium">{service.code}</span> de {service.client.name}.{' '}
          {SERVICE_EXIT_TRANSITION_PROMPT[transition]}
          {showsReopenNote && ` ${REOPEN_AVAILABLE_NOTE}`}
        </p>

        <Textarea
          id="service-exit-reason"
          label={SERVICE_EXIT_REASON_LABEL[transition]}
          rows={3}
          maxLength={STATUS_NOTE_MAX_LENGTH}
          showCounter
          value={note}
          helperText={`Mínimo ${SERVICE_EXIT_REASON_MIN_LENGTH} caracteres. Queda en la bitácora del viaje.`}
          error={errors.note?.message}
          register={register('note')}
          sanitize={stripControlChars}
          disabled={isPending}
        />

        {conflict && transition === 'REOPENED' ? (
          /*
           * Al reabrir, el usuario NO eligió a nadie: los recursos son los que el viaje
           * ya tenía. Así que sus únicas salidas son forzar o no reabrir, y el texto no
           * puede sugerirle una tercera (cambiar el conductor) que acá no existe.
           */
          <ResourceConflictAlert
            error={conflict}
            forceLabel="Reabrir de todos modos"
            forceConsequence={REOPEN_FORCE_WARNING}
            isPending={isPending}
            onForce={handleSubmit((values) => submit(values, true))}
          />
        ) : (
          changeStatus.error && (
            <ServiceStatusErrorAlert
              error={changeStatus.error}
              fallback={SERVICE_EXIT_FAILURE_MESSAGE[transition]}
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
          )
        )}

        <div className="flex justify-end gap-3 pt-2">
          {/* "Volver" y no "Cancelar": es la única pantalla del sistema donde esa
              palabra nombra las dos cosas a la vez. */}
          <Button variant="secondary" onClick={onClose}>
            Volver
          </Button>
          {/* Reabrir REPARA: se llega desde un botón primario en la barra y sería
              incoherente que acá se pintara de alarma. Las otras dos sí sacan el viaje
              del circuito. */}
          <Button
            ref={submitButton}
            type="submit"
            variant={transition === 'REOPENED' ? 'primary' : 'danger'}
            disabled={isPending}
          >
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
