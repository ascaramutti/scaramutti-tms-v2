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
  /** Si viene, el Cargo queda fijo y este texto dice por qué. */
  roleLockedReason?: string
  disabled: boolean
}

/**
 * Los datos del trabajador, en el orden de la ficha. El alta y la edición comparten este
 * grupo; lo propio de cada pantalla vive en su contenedor.
 */
export function WorkerFormFields({ documentTypes, roleOptions, roleLockedReason, disabled }: WorkerFormFieldsProps) {
  const {
    register,
    control,
    formState: { errors },
  } = useFormContext<WorkerFormValues>()

  return (
    <Card as="fieldset" padding="md" className="min-w-0">
      <legend className="float-left w-full text-sm font-semibold text-fg">Datos del trabajador</legend>
      <div className="clear-both grid grid-cols-1 gap-4 pt-4 sm:grid-cols-2">
        {/* Las tres partes del nombre en una fila, antes del documento: se leen como un solo dato. */}
        <div className="grid grid-cols-1 gap-4 sm:col-span-2 sm:grid-cols-3">
          <TextField id="trabajador-first-name" label="Nombres" error={errors.firstName?.message}
            disabled={disabled} register={register('firstName')} />
          <TextField id="trabajador-paternal-last-name" label="Apellido paterno"
            error={errors.paternalLastName?.message} disabled={disabled} register={register('paternalLastName')} />
          <TextField id="trabajador-maternal-last-name" label="Apellido materno (opcional)"
            error={errors.maternalLastName?.message} disabled={disabled} register={register('maternalLastName')} />
        </div>
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
          helperText={roleLockedReason}
          disabled={disabled || roleLockedReason !== undefined}
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
