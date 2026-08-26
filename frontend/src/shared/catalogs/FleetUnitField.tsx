import { useMemo, useState } from 'react'
import type { FleetUnitKind, FleetUnitRef, FleetUnitResponse } from '../../api'
import { Combobox, type ComboboxOption } from '../ui/Combobox'
import { useFleetUnits } from './useFleetUnits'
import { fleetUnitKey, fleetUnitLabel } from './fleetUnit'

interface FleetUnitFieldProps {
  id: string
  label?: string
  ariaLabel?: string
  /**
   * Subtipo a ofrecer. Omitido, entran los tres. Se lo pasa al hook, así que dos
   * campos con distinto subtipo consultan y cachean por separado.
   */
  kind?: FleetUnitKind
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
  /**
   * Qué ofrece el campo. Sin valor por defecto a propósito: el texto depende del
   * subtipo, y uno genérico que nombre las tres clases sería falso en un campo que
   * pide solo tractos.
   */
  placeholder: string
  /**
   * Qué se le dice al usuario cuando el catálogo no carga. También obligatorio, y
   * por el mismo motivo: la consecuencia depende del consumidor (donde la unidad es
   * opcional se puede seguir sin ella, y donde es obligatoria no), así que un
   * default lo dejaría sin decir ninguna de las dos cosas justo cuando importa.
   */
  loadErrorText: string
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
 * Selección de una unidad de flota (tracto, carreta o escolta). El contrato de
 * `/fleet-units` no acepta `q`: la flota se trae entera y este combobox filtra en
 * cliente por etiqueta, placa, marca o modelo. NO ofrece crear al vuelo: una unidad
 * se da de alta en el módulo de flota y no desde el formulario que la elige
 * (RN-WH9).
 * Componente controlado.
 *
 * Vive en `shared/` y no en un módulo porque la misma flota la va a elegir, además
 * del retiro de almacén, la asignación de recursos a un viaje, que necesita acotar
 * el subtipo y decir lo suyo cuando el catálogo no carga.
 */
export function FleetUnitField({
  id,
  label,
  ariaLabel,
  kind,
  selected,
  onSelectedChange,
  placeholder,
  loadErrorText,
}: FleetUnitFieldProps) {
  const [query, setQuery] = useState('')
  const { data, isLoading, isError } = useFleetUnits({ kind })

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
        placeholder={placeholder}
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
          {loadErrorText}
        </p>
      )}
    </div>
  )
}
