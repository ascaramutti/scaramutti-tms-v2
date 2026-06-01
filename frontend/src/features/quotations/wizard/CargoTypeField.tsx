import { useState } from 'react'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import {
  CARGO_TYPE_SEARCH_MIN_LENGTH,
  useCargoTypesSearch,
} from '../../cargotypes/hooks/useCargoTypesSearch'
import { CrearCargoTypeModal } from './CrearCargoTypeModal'
import type { CargoTypeResponse } from '../../../api'

interface CargoTypeFieldProps {
  /** Id único del input (hay uno por ítem). */
  id: string
  /** Tipo de carga elegido (id) y su nombre (para el chip). El nombre vive en el
   * form del ítem para sobrevivir el cambio de step. */
  value: number | null
  valueName?: string
  /** Pasa el tipo de carga COMPLETO (para precargar peso/dimensiones estándar) o null al limpiar. */
  onChange: (cargoType: CargoTypeResponse | null) => void
  onBlur?: () => void
  error?: string
}

/**
 * Combobox de tipo de carga (búsqueda async, minLength 3) + creación al vuelo, para
 * los ítems de tipo SERVICIO. Mismo patrón que el buscador de cliente.
 */
export function CargoTypeField({ id, value, valueName, onChange, onBlur, error }: CargoTypeFieldProps) {
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const debouncedQuery = useDebouncedValue(query, 300)
  const { data, isFetching } = useCargoTypesSearch(debouncedQuery)

  const cargoTypes = data?.content ?? []
  const options: ComboboxOption[] = cargoTypes.map((cargo) => ({ id: cargo.id, label: cargo.name }))
  const selected = value ? { id: value, label: valueName ?? '' } : null

  function handleSelect(option: ComboboxOption) {
    const cargoType = cargoTypes.find((cargo) => cargo.id === option.id)
    if (cargoType) onChange(cargoType)
  }

  return (
    <>
      <Combobox
        id={id}
        label="Tipo de carga"
        placeholder="Busca el tipo de carga…"
        options={options}
        selected={selected}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={() => onChange(null)}
        onBlur={onBlur}
        loading={isFetching}
        minChars={CARGO_TYPE_SEARCH_MIN_LENGTH}
        minCharsHint={`Ingresa al menos ${CARGO_TYPE_SEARCH_MIN_LENGTH} caracteres para buscar.`}
        emptyText="No se encontraron tipos de carga."
        error={error}
        createLabel="Nuevo tipo de carga"
        onCreateClick={() => setModalOpen(true)}
      />
      {modalOpen && (
        <CrearCargoTypeModal
          initialName={query}
          onClose={() => setModalOpen(false)}
          onCreated={(cargoType) => {
            onChange(cargoType)
            setModalOpen(false)
          }}
        />
      )}
    </>
  )
}
