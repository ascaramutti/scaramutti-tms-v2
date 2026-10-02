import { useFormContext, useWatch } from 'react-hook-form'
import type { DriverProfileMode } from '../../../api'
import { Card } from '../../../shared/ui/Card'
import { FIELD_CHECKBOX } from '../../../shared/ui/fieldClasses'
import { TextField } from '../../../shared/ui/TextField'
import type { WorkerFormValues } from '../schemas/worker.schema'

interface WorkerDriverSectionProps {
  /** La modalidad del cargo elegido; sin cargo o sin licencia, la sección no existe. */
  profile: DriverProfileMode | undefined
  disabled: boolean
}

/**
 * La licencia de conducir, según lo que pide el cargo. Solo se oculta: lo escrito
 * sigue en el formulario si se vuelve a un cargo que la lleva. La disponibilidad no
 * se elige en el alta; la edición decide la suya.
 */
export function WorkerDriverSection({ profile, disabled }: WorkerDriverSectionProps) {
  const {
    register,
    control,
    formState: { errors },
  } = useFormContext<WorkerFormValues>()
  const hasLicense = useWatch({ control, name: 'hasLicense' })
  const hasLicenseField = register('hasLicense')
  if (profile !== 'REQUIRED' && profile !== 'OPTIONAL') return null
  const showFields = profile === 'REQUIRED' || hasLicense

  return (
    <Card as="fieldset" padding="md" className="min-w-0">
      <legend className="float-left w-full text-sm font-semibold text-fg">Licencia de conducir</legend>
      {profile === 'OPTIONAL' && (
        // Como Cancelar y Guardar: presionarla no le quita el foco al campo que se deja antes del
        // clic, así su aviso no corre la casilla. Después del clic el foco va a la casilla: si estaba
        // en los campos que se ocultan, no se pierde en la página.
        <label
          className="clear-both flex items-center gap-2 pt-4 text-sm text-fg-body"
          onMouseDown={(event) => event.preventDefault()}
        >
          <input
            type="checkbox"
            className={FIELD_CHECKBOX}
            aria-controls={hasLicense ? 'trabajador-licencia-campos' : undefined}
            disabled={disabled}
            {...hasLicenseField}
            onChange={(event) => {
              event.target.focus()
              void hasLicenseField.onChange(event)
            }}
          />
          Tiene licencia
        </label>
      )}
      {showFields && (
        <div id="trabajador-licencia-campos" className="clear-both grid grid-cols-1 gap-4 pt-4 sm:grid-cols-2">
          <TextField
            id="trabajador-license-number"
            label="N.° de licencia"
            error={errors.driver?.licenseNumber?.message}
            disabled={disabled}
            register={register('driver.licenseNumber')}
          />
          <TextField
            id="trabajador-license-category"
            label="Categoría (opcional)"
            error={errors.driver?.licenseCategory?.message}
            disabled={disabled}
            register={register('driver.licenseCategory')}
          />
        </div>
      )}
    </Card>
  )
}
