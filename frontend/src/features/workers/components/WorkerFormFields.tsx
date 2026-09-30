import { useFormContext } from 'react-hook-form'
import type { DocumentTypeResponse, RoleResponse } from '../../../api'
import { Card } from '../../../shared/ui/Card'
import { DateField } from '../../../shared/ui/DateField'
import { SelectField } from '../../../shared/ui/SelectField'
import { TextField } from '../../../shared/ui/TextField'
import { todayInLima } from '../../../shared/utils/limaDate'
import type { WorkerFormValues } from '../schemas/worker.schema'

interface WorkerFormFieldsProps {
  documentTypes: readonly DocumentTypeResponse[]
  /** Solo los cargos que la sesión puede asignar. */
  roleOptions: readonly RoleResponse[]
  disabled: boolean
}

/**
 * Los datos del trabajador, en el orden de la ficha. La edición reusará este grupo;
 * lo propio de cada pantalla vive en su contenedor.
 */
export function WorkerFormFields({ documentTypes, roleOptions, disabled }: WorkerFormFieldsProps) {
  const {
    register,
    control,
    formState: { errors },
  } = useFormContext<WorkerFormValues>()

  return (
    <Card as="fieldset" padding="md" className="min-w-0">
      <legend className="float-left w-full text-sm font-semibold text-fg">Datos del trabajador</legend>
      <div className="clear-both grid grid-cols-1 gap-4 pt-4 sm:grid-cols-2">
        <TextField id="trabajador-first-name" label="Nombre" error={errors.firstName?.message}
          disabled={disabled} register={register('firstName')} />
        <TextField id="trabajador-last-name" label="Apellido" error={errors.lastName?.message}
          disabled={disabled} register={register('lastName')} />
        <SelectField<WorkerFormValues>
          id="trabajador-doc-type"
          label="Tipo de documento"
          name="documentTypeId"
          control={control}
          options={documentTypes.map((candidate) => ({ value: candidate.id, label: candidate.name }))}
          error={errors.documentTypeId?.message}
          disabled={disabled}
        />
        <TextField
          id="trabajador-doc-number"
          label="Número de documento"
          error={errors.documentNumber?.message}
          disabled={disabled}
          register={register('documentNumber')}
        />
        <SelectField<WorkerFormValues>
          id="trabajador-role"
          label="Cargo"
          name="role"
          control={control}
          placeholder="Elige el cargo"
          options={roleOptions.map((role) => ({ value: role.name, label: role.description }))}
          error={errors.role?.message}
          disabled={disabled}
        />
        <DateField<WorkerFormValues>
          id="trabajador-hire-date"
          label="Fecha de ingreso"
          name="hireDate"
          control={control}
          max={todayInLima()}
          error={errors.hireDate?.message}
          disabled={disabled}
        />
        <TextField
          id="trabajador-phone"
          label="Teléfono (opcional)"
          type="tel"
          autoComplete="tel"
          error={errors.phone?.message}
          disabled={disabled}
          register={register('phone')}
        />
      </div>
    </Card>
  )
}
