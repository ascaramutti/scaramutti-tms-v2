import { useMemo, useState } from 'react'
import type { DriverRef, DriverResponse } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDrivers } from '../hooks/useDrivers'
import { DRIVER_STATUS_LABELS } from '../status/resourcePresentation'

interface DriverFieldProps {
  id: string
  label: string
  /** Conductor elegido. Vive en el consumidor. */
  selected: DriverRef | null
  onSelectedChange: (driver: DriverResponse | null) => void
  placeholder: string
  loadErrorText: string
  error?: string
}

/**
 * Sublínea de una opción: la licencia y la disponibilidad.
 *
 * La disponibilidad se MUESTRA y no filtra ni deshabilita. El contrato es explícito
 * en que el servidor no la valida, así que un conductor no disponible se puede
 * asignar igual: el catálogo de estados existe para ordenar la decisión de quien
 * despacha, no para prohibirla. Un campo que filtrara por disponible dejaría al
 * despacho sin poder hacer lo que el backend acepta.
 */
function toOption(driver: DriverResponse): ComboboxOption {
  return {
    id: driver.id,
    label: driver.fullName,
    sublabel: `${driver.licenseNumber} · ${DRIVER_STATUS_LABELS[driver.status]}`,
  }
}

/**
 * Selección de un conductor. El contrato de `/drivers` no acepta `q` ni pagina, así
 * que el padrón se trae entero y este combobox filtra en cliente por nombre, número
 * de licencia o categoría.
 *
 * No ofrece dar de alta al vuelo: un conductor es una fila de `public.drivers` ligada
 * a un trabajador, y el contrato deja su alta para la futura gestión de flota y
 * personal, que todavía no existe.
 */
export function DriverField({
  id,
  label,
  selected,
  onSelectedChange,
  placeholder,
  loadErrorText,
  error,
}: DriverFieldProps) {
  const [query, setQuery] = useState('')
  const { data, isLoading, isError } = useDrivers()

  const options = useMemo(() => {
    const drivers = data ?? []
    const term = query.trim().toLowerCase()
    const matches = term
      ? drivers.filter((driver) =>
          [driver.fullName, driver.licenseNumber, driver.licenseCategory]
            .join(' ')
            .toLowerCase()
            .includes(term),
        )
      : drivers
    return matches.map(toOption)
  }, [data, query])

  function handleSelect(option: ComboboxOption) {
    const driver = data?.find((item) => item.id === option.id)
    if (driver) onSelectedChange(driver)
  }

  // El elegido puede no estar en el catálogo (quedó inactivo después de asignarlo),
  // y aun así tiene que seguir viéndose. Solo trae el nombre, no la licencia.
  const selectedOption: ComboboxOption | null = selected
    ? { id: selected.id, label: selected.fullName }
    : null

  return (
    <div>
      <Combobox
        id={id}
        label={label}
        placeholder={placeholder}
        options={options}
        selected={selectedOption}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={() => onSelectedChange(null)}
        loading={isLoading}
        emptyText="No se encontraron conductores."
        error={error}
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-warning">
          {loadErrorText}
        </p>
      )}
    </div>
  )
}
