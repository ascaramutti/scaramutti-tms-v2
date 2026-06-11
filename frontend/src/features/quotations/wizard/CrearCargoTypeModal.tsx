import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Modal } from '../../../shared/ui/Modal'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { useCreateCargoType } from '../../cargotypes/hooks/useCreateCargoType'
import {
  createCargoTypeSchema,
  type CreateCargoTypeInput,
} from '../../cargotypes/schemas/cargo-type.schema'
import type { CargoTypeResponse } from '../../../api'

interface CrearCargoTypeModalProps {
  /** Texto tipeado en el combobox, para precargar el nombre. */
  initialName?: string
  onClose: () => void
  onCreated: (cargoType: CargoTypeResponse) => void
}

const CARGO_TYPE_FIELDS = [
  'name',
  'description',
  'standardWeight',
  'standardLength',
  'standardWidth',
  'standardHeight',
] as const

const PRIMARY =
  'inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-blue-300'
const SECONDARY =
  'inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500'

/** Empty → `null` (dimensiones estándar opcionales). */
function nullableNum(value: string): number | null {
  return value === '' ? null : Number(value)
}
/** Empty → `undefined` (peso requerido: zod muestra el mensaje). */
function requiredNum(value: string): number | undefined {
  return value === '' ? undefined : Number(value)
}

/**
 * Modal de creación de tipo de carga al vuelo. `POST /cargo-types`; un 409 (nombre
 * duplicado, `CGT-001`) se rutea al campo nombre con `handleApiFormError`. Permite
 * cargar todas las características del catálogo: solo nombre y peso son obligatorios.
 */
export function CrearCargoTypeModal({ initialName = '', onClose, onCreated }: CrearCargoTypeModalProps) {
  const createCargoType = useCreateCargoType()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CreateCargoTypeInput>({
    resolver: zodResolver(createCargoTypeSchema),
    defaultValues: {
      name: initialName,
      description: '',
      standardWeight: 0,
      standardLength: null,
      standardWidth: null,
      standardHeight: null,
    },
  })

  const onSubmit = handleSubmit(async (values) => {
    try {
      const cargoType = await createCargoType.mutateAsync({
        name: values.name,
        description: values.description || null,
        standardWeight: values.standardWeight,
        standardLength: values.standardLength ?? null,
        standardWidth: values.standardWidth ?? null,
        standardHeight: values.standardHeight ?? null,
      })
      onCreated(cargoType)
    } catch (error) {
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo crear el tipo de carga. Intenta de nuevo.',
        codeFieldMap: { 'CGT-001': 'name' },
        allowedFields: CARGO_TYPE_FIELDS,
      })
    }
  })

  return (
    <Modal isOpen onClose={onClose} title="Nuevo tipo de carga">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField
          id="cargo-name"
          label="Nombre"
          error={errors.name?.message}
          disabled={isSubmitting}
          register={register('name')}
        />
        <TextField
          id="cargo-description"
          label="Descripción (opcional)"
          error={errors.description?.message}
          disabled={isSubmitting}
          register={register('description')}
        />
        <TextField
          id="cargo-weight"
          label="Peso estándar (kg)"
          type="number"
          min={0}
          step={0.01}
          error={errors.standardWeight?.message}
          disabled={isSubmitting}
          register={register('standardWeight', { setValueAs: requiredNum })}
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <TextField
            id="cargo-length"
            label="Largo estándar (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.standardLength?.message}
            disabled={isSubmitting}
            register={register('standardLength', { setValueAs: nullableNum })}
          />
          <TextField
            id="cargo-width"
            label="Ancho estándar (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.standardWidth?.message}
            disabled={isSubmitting}
            register={register('standardWidth', { setValueAs: nullableNum })}
          />
          <TextField
            id="cargo-height"
            label="Alto estándar (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.standardHeight?.message}
            disabled={isSubmitting}
            register={register('standardHeight', { setValueAs: nullableNum })}
          />
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={onClose} className={SECONDARY}>
            Cancelar
          </button>
          <button type="submit" disabled={isSubmitting} className={PRIMARY}>
            {isSubmitting ? (
              <>
                <Spinner size={16} label="Creando" />
                Creando…
              </>
            ) : (
              'Crear tipo de carga'
            )}
          </button>
        </div>
      </form>
    </Modal>
  )
}
