import { useMemo, useState } from 'react'
import type { FleetUnitRef, FleetUnitResponse } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useFleetUnits } from '../hooks/useFleetUnits'
import { fleetUnitKey, fleetUnitLabel } from '../utils/fleetUnit'

interface FleetUnitFieldProps {
  id: string
  label?: string
  ariaLabel?: string
  /**
   * Unidad elegida (opcional). Vive en el consumidor. Acepta la referencia mínima
   * `(kind, id, plate)` porque el detalle de un retiro devuelve eso y no la unidad
   * completa: al precargar la edición la marca y el modelo no están, y solo se usan
   * como sublínea. Si la unidad original quedó inactiva no aparece en el catálogo,
   * pero se sigue viendo como seleccionada, que es lo correcto: se conserva salvo
   * que el usuario la cambie.
   */
  selected: (FleetUnitRef & Partial<Pick<FleetUnitResponse, 'brand' | 'model'>>) | null
  onSelectedChange: (fleetUnit: FleetUnitResponse | null) => void
}

function toOption(
  fleetUnit: FleetUnitRef & Partial<Pick<FleetUnitResponse, 'brand' | 'model'>>,
): ComboboxOption {
  const brandModel = [fleetUnit.brand, fleetUnit.model].filter(Boolean).join(' ')
  return {
    // Clave compuesta `kind:id`: el id suelto colisiona entre subtipos (un tracto
    // y una carreta pueden compartir id), y elegir por id devolvería otra unidad.
    id: fleetUnitKey(fleetUnit),
    label: fleetUnitLabel(fleetUnit),
    sublabel: brandModel || undefined,
  }
}

/**
 * Selección OPCIONAL de la unidad de flota a la que se carga el retiro (tracto,
 * carreta o escolta). El contrato de `/fleet-units` no acepta `q`: la flota se
 * trae entera y este combobox filtra en cliente por etiqueta, placa, marca o
 * modelo. NO ofrece crear al vuelo (RN-WH9). Componente controlado.
 */
export function FleetUnitField({
  id,
  label,
  ariaLabel,
  selected,
  onSelectedChange,
}: FleetUnitFieldProps) {
  const [query, setQuery] = useState('')
  const { data, isLoading, isError } = useFleetUnits()

  const options = useMemo(() => {
    const units = data ?? []
    const term = query.trim().toLowerCase()
    const matches = term
      ? units.filter((unit) =>
          [fleetUnitLabel(unit), unit.plate, unit.brand ?? '', unit.model ?? '']
            .join(' ')
            .toLowerCase()
            .includes(term),
        )
      : units
    return matches.map(toOption)
  }, [data, query])

  function handleSelect(option: ComboboxOption) {
    // Resuelve por la clave compuesta, no por id: dos subtipos pueden compartir id.
    const fleetUnit = data?.find((item) => fleetUnitKey(item) === option.id)
    if (fleetUnit) onSelectedChange(fleetUnit)
  }

  return (
    <div>
      <Combobox
        id={id}
        label={label}
        ariaLabel={ariaLabel}
        placeholder="Tracto, carreta o escolta (opcional)…"
        options={options}
        selected={selected ? toOption(selected) : null}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={() => onSelectedChange(null)}
        loading={isLoading}
        emptyText="No se encontraron unidades."
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-amber-700">
          No se pudieron cargar las unidades de flota. El retiro se puede registrar sin unidad.
        </p>
      )}
    </div>
  )
}
