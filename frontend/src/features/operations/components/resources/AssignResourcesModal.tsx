import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import type { DriverResponse, FleetUnitResponse } from '../../../../api'
import { FleetUnitField } from '../../../../shared/catalogs/FleetUnitField'
import { Modal } from '../../../../shared/ui/Modal'
import { Spinner } from '../../../../shared/ui/Spinner'
import { Textarea } from '../../../../shared/ui/Textarea'
import { stripControlChars } from '../../../../shared/utils/sanitizeText'
import { DriverField } from '../DriverField'
import { useAssignServiceResources } from '../../hooks/useAssignServiceResources'
import {
  ASSIGNMENT_NOTE_MAX_LENGTH,
  DEFAULT_ASSIGN_RESOURCES_VALUES,
  assignResourcesFormSchema,
  toAssignResourcesRequest,
  type AssignResourcesFormInput,
} from '../../schemas/assign-resources.schema'
import {
  getServiceOperationError,
  type ServiceOperationError,
} from '../../utils/serviceResourceConflict'
import { ResourceConflictAlert } from './ResourceConflictAlert'
import { Button } from '../../../../shared/ui/Button'

interface AssignResourcesModalProps {
  isOpen: boolean
  onClose: () => void
  serviceId: number
  serviceCode: string
}

/**
 * Asignación de los recursos principales de un viaje.
 *
 * Se monta solo cuando está abierto: react-hook-form congela sus valores iniciales al
 * montar, así que un modal siempre montado reabriría con lo que se eligió la vez
 * anterior.
 */
export function AssignResourcesModal(props: AssignResourcesModalProps) {
  if (!props.isOpen) return null
  return <AssignResourcesForm {...props} />
}

function AssignResourcesForm({ onClose, serviceId, serviceCode }: AssignResourcesModalProps) {
  const assignResources = useAssignServiceResources(serviceId)
  const [driver, setDriver] = useState<DriverResponse | null>(null)
  const [tractor, setTractor] = useState<FleetUnitResponse | null>(null)
  const [trailer, setTrailer] = useState<FleetUnitResponse | null>(null)
  const [operationError, setOperationError] = useState<ServiceOperationError | null>(null)
  const [genericError, setGenericError] = useState<string | null>(null)

  const {
    control,
    register,
    handleSubmit,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<AssignResourcesFormInput>({
    resolver: zodResolver(assignResourcesFormSchema),
    mode: 'onTouched',
    defaultValues: DEFAULT_ASSIGN_RESOURCES_VALUES,
  })

  // `useWatch` y no `watch`: el segundo devuelve una función que el compilador de
  // React no puede memoizar, y saltea la optimización del componente entero.
  const note = useWatch({ control, name: 'note' })

  const isPending = isSubmitting || assignResources.isPending

  /**
   * Cambiar cualquier selección descarta el error anterior.
   *
   * Se limpia acá, donde nace el cambio, y no en un efecto que observe los tres ids:
   * un efecto que llama a `setState` encadena renders, y además tapaba el motivo. Sin
   * esta limpieza, elegir otro conductor y volver a enviar mostraría el choque del
   * que ya se cambió, y el botón de forzar seguiría ofreciendo pisar algo que ya no
   * se está pisando.
   */
  function clearErrors() {
    setOperationError(null)
    setGenericError(null)
  }

  function applyDriver(value: DriverResponse | null) {
    clearErrors()
    setDriver(value)
    setValue('driverId', value?.id ?? (null as unknown as number), {
      shouldValidate: true,
      shouldTouch: true,
    })
  }

  function applyTractor(value: FleetUnitResponse | null) {
    clearErrors()
    setTractor(value)
    setValue('tractorId', value?.id ?? (null as unknown as number), {
      shouldValidate: true,
      shouldTouch: true,
    })
  }

  function applyTrailer(value: FleetUnitResponse | null) {
    clearErrors()
    setTrailer(value)
    setValue('trailerId', value?.id ?? null, { shouldValidate: true, shouldTouch: true })
  }

  /**
   * `force` es argumento y no un campo del formulario: guardado en el form quedaría
   * pegado en `true` después del primer forzado, y una selección posterior viajaría
   * forzada sin que nadie lo pidiera.
   */
  function submit(values: AssignResourcesFormInput, force: boolean) {
    clearErrors()
    assignResources.mutate(toAssignResourcesRequest(values, force), {
      onSuccess: () => {
        toast.success(`Recursos asignados a ${serviceCode}. El viaje quedó pendiente de inicio.`)
        onClose()
      },
      onError: (error) => {
        const operation = getServiceOperationError(error)
        // Se muestra el texto del backend SIEMPRE que lo traiga, sea el código que
        // sea: el conflicto, el estado que no admite la acción, el recurso dado de
        // baja o el veto de rol. Solo la caída de red y el 500 sin cuerpo, que no
        // traen nada que mostrar, caen al mensaje propio, y es el único caso en el
        // que inventar el texto es correcto.
        if (operation?.detail) {
          setOperationError(operation)
          return
        }
        // El literal directo y no `getApiErrorMessage`: acá se llega SOLO cuando el
        // backend no trajo `detail`, que es exactamente la condición con la que ese
        // helper decide devolver su respaldo. Llamarlo sugeriría que puede devolver
        // otra cosa.
        setGenericError('No se pudieron asignar los recursos. Intenta de nuevo.')
      },
    })
  }

  const onSubmit = handleSubmit((values) => submit(values, false))

  return (
    <Modal isOpen onClose={onClose} title="Asignar recursos" size="lg">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <p className="text-xs text-slate-500">
          Al asignar, el viaje pasa a pendiente de inicio.
        </p>

        <DriverField
          id="assign-driver"
          label="Conductor"
          selected={driver}
          onSelectedChange={applyDriver}
          placeholder="Busca por nombre o licencia…"
          loadErrorText="No se pudieron cargar los conductores, y el viaje no se puede asignar sin uno."
          error={errors.driverId?.message}
        />

        <FleetUnitField
          id="assign-tractor"
          label="Tracto"
          kind="TRACTOR"
          selected={tractor}
          onSelectedChange={applyTractor}
          placeholder="Busca por placa, marca o modelo…"
          loadErrorText="No se pudieron cargar los tractos, y el viaje no se puede asignar sin uno."
          error={errors.tractorId?.message}
        />

        <FleetUnitField
          id="assign-trailer"
          label="Carreta (opcional)"
          kind="TRAILER"
          selected={trailer}
          onSelectedChange={applyTrailer}
          placeholder="Busca por placa, marca o modelo…"
          loadErrorText="No se pudieron cargar las carretas. El viaje se puede asignar sin carreta."
        />

        <Textarea
          id="assign-note"
          label="Nota (opcional)"
          rows={2}
          maxLength={ASSIGNMENT_NOTE_MAX_LENGTH}
          showCounter
          value={note}
          helperText="Queda registrada en la bitácora del viaje."
          error={errors.note?.message}
          disabled={isPending}
          sanitize={stripControlChars}
          register={register('note')}
        />

        {operationError && (
          <ResourceConflictAlert
            error={operationError}
            forceLabel="Asignar de todos modos"
            isPending={isPending}
            onForce={handleSubmit((values) => submit(values, true))}
          />
        )}

        {genericError && (
          <p
            role="alert"
            className="rounded-lg border border-red-200 bg-red-50 px-4 py-2.5 text-sm text-red-700"
          >
            {genericError}
          </p>
        )}

        <div className="flex justify-end gap-3 pt-2">
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" type="submit" disabled={isPending}>
            {isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-white" /> Asignando…
              </>
            ) : (
              'Asignar recursos'
            )}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
