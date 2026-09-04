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
import { useAddServiceResources } from '../../hooks/useAddServiceResources'
import {
  DEFAULT_ADD_RESOURCES_VALUES,
  REINFORCEMENT_REASON_MAX_LENGTH,
  REINFORCEMENT_REASON_MIN_LENGTH,
  addResourcesFormSchema,
  toAddResourcesRequest,
  type AddResourcesFormInput,
} from '../../schemas/add-resources.schema'
import {
  getServiceOperationError,
  type ServiceOperationError,
} from '../../utils/serviceResourceConflict'
import { ResourceConflictAlert } from './ResourceConflictAlert'
import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Alert'

interface AddResourcesModalProps {
  isOpen: boolean
  onClose: () => void
  serviceId: number
  serviceCode: string
}

/**
 * Alta de un refuerzo sobre un viaje en ruta: el relevo de un conductor que agotó su
 * descanso, la unidad de apoyo que sale a un varado.
 *
 * Se monta solo cuando está abierto, por lo mismo que la asignación: react-hook-form
 * congela sus valores iniciales al montar.
 */
export function AddResourcesModal(props: AddResourcesModalProps) {
  if (!props.isOpen) return null
  return <AddResourcesForm {...props} />
}

function AddResourcesForm({ onClose, serviceId, serviceCode }: AddResourcesModalProps) {
  const addResources = useAddServiceResources(serviceId)
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
  } = useForm<AddResourcesFormInput>({
    resolver: zodResolver(addResourcesFormSchema),
    mode: 'onTouched',
    defaultValues: DEFAULT_ADD_RESOURCES_VALUES,
  })

  const reason = useWatch({ control, name: 'reason' })
  const isPending = isSubmitting || addResources.isPending

  function clearErrors() {
    setOperationError(null)
    setGenericError(null)
  }

  function applyDriver(value: DriverResponse | null) {
    clearErrors()
    setDriver(value)
    setValue('driverId', value?.id ?? null, { shouldValidate: true, shouldTouch: true })
  }

  function applyTractor(value: FleetUnitResponse | null) {
    clearErrors()
    setTractor(value)
    setValue('tractorId', value?.id ?? null, { shouldValidate: true, shouldTouch: true })
  }

  function applyTrailer(value: FleetUnitResponse | null) {
    clearErrors()
    setTrailer(value)
    setValue('trailerId', value?.id ?? null, { shouldValidate: true, shouldTouch: true })
  }

  function submit(values: AddResourcesFormInput, force: boolean) {
    clearErrors()
    addResources.mutate(toAddResourcesRequest(values, force), {
      onSuccess: () => {
        toast.success(`Refuerzo agregado a ${serviceCode}.`)
        onClose()
      },
      onError: (error) => {
        const operation = getServiceOperationError(error)
        if (operation?.detail) {
          setOperationError(operation)
          return
        }
        // El literal directo y no `getApiErrorMessage`: acá se llega SOLO cuando el
        // backend no trajo `detail`, que es exactamente la condición con la que ese
        // helper decide devolver su respaldo. Llamarlo sugeriría que puede devolver
        // otra cosa.
        setGenericError('No se pudo agregar el refuerzo. Intenta de nuevo.')
      },
    })
  }

  const onSubmit = handleSubmit((values) => submit(values, false))

  return (
    <Modal isOpen onClose={onClose} title="Agregar refuerzo" size="lg">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <p className="text-xs text-fg-muted">
          Recursos de apoyo para un viaje que ya está en ruta. Los principales no se
          reemplazan: el refuerzo se suma.
        </p>

        {/* El error de "al menos uno" es del GRUPO, así que se muestra una vez arriba
            de los tres campos y no repetido bajo cada uno. */}
        {errors.driverId?.message && (
          <p role="alert" className="text-xs text-danger">
            {errors.driverId.message}
          </p>
        )}

        <DriverField
          id="add-driver"
          label="Conductor adicional"
          selected={driver}
          onSelectedChange={applyDriver}
          placeholder="Busca por nombre o licencia…"
          loadErrorText="No se pudieron cargar los conductores. Se puede sumar un tracto o una carreta."
        />

        <FleetUnitField
          id="add-tractor"
          label="Tracto adicional"
          kind="TRACTOR"
          selected={tractor}
          onSelectedChange={applyTractor}
          placeholder="Busca por placa, marca o modelo…"
          loadErrorText="No se pudieron cargar los tractos. Se puede sumar un conductor o una carreta."
        />

        <FleetUnitField
          id="add-trailer"
          label="Carreta adicional"
          kind="TRAILER"
          selected={trailer}
          onSelectedChange={applyTrailer}
          placeholder="Busca por placa, marca o modelo…"
          loadErrorText="No se pudieron cargar las carretas. Se puede sumar un conductor o un tracto."
        />

        <Textarea
          id="add-reason"
          label="Motivo"
          rows={2}
          maxLength={REINFORCEMENT_REASON_MAX_LENGTH}
          showCounter
          value={reason}
          helperText={`Mínimo ${REINFORCEMENT_REASON_MIN_LENGTH} caracteres. Queda registrado en la bitácora del viaje.`}
          error={errors.reason?.message}
          disabled={isPending}
          sanitize={stripControlChars}
          register={register('reason')}
        />

        {operationError && (
          <ResourceConflictAlert
            error={operationError}
            forceLabel="Agregar de todos modos"
            isPending={isPending}
            onForce={handleSubmit((values) => submit(values, true))}
          />
        )}

        {genericError && (
          <Alert as="p" role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            {genericError}
          </Alert>
        )}

        <div className="flex justify-end gap-3 pt-2">
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" type="submit" disabled={isPending}>
            {isPending ? (
              <>
                <Spinner size={16} className="mr-2 text-on-solid" /> Agregando…
              </>
            ) : (
              'Agregar refuerzo'
            )}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
