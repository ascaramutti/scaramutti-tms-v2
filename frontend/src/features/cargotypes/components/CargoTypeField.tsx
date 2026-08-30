import { useState } from 'react'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import {
  CARGO_TYPE_SEARCH_MIN_LENGTH,
  useCargoTypesSearch,
} from '../hooks/useCargoTypesSearch'
import { CargoTypeCreateModal } from './CargoTypeCreateModal'
import type { CargoTypeResponse } from '../../../api'

interface CargoTypeFieldProps {
  /** Id único del input: puede haber varios en la misma pantalla. */
  id: string
  /** Tipo de carga elegido (id) y su nombre (para el chip). El componente no guarda
   * el nombre: lo recibe, para poder mostrarlo sin volver a buscarlo cuando se
   * remonta (el consumidor lo persiste junto al id). */
  value: number | null
  valueName?: string
  /** Pasa el tipo de carga COMPLETO (para precargar peso/dimensiones estándar) o null al limpiar. */
  onChange: (cargoType: CargoTypeResponse | null) => void
  onBlur?: () => void
  error?: string
  /**
   * Si se ofrece el alta al vuelo. Por omisión sí, que es como se comportaba cuando
   * el único consumidor era el asistente de cotizaciones. Las pantallas cuyo rol
   * puede quedar sin permiso de alta lo pasan explícito: el servidor solo admite el
   * `POST /cargo-types` a algunos roles, y ofrecer el botón al resto los manda a un
   * 403. En falso el buscador sigue funcionando: se saca el botón, no el campo.
   */
  canCreate?: boolean
  /** Bloquea el campo, p. ej. mientras el formulario se está enviando. */
  disabled?: boolean
}

/**
 * Combobox de tipo de carga (búsqueda async, minLength 3) + creación al vuelo.
 * Mismo patrón que el buscador de cliente.
 */
export function CargoTypeField({
  id,
  value,
  valueName,
  onChange,
  onBlur,
  error,
  canCreate = true,
  disabled = false,
}: CargoTypeFieldProps) {
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
        disabled={disabled}
        loading={isFetching}
        minChars={CARGO_TYPE_SEARCH_MIN_LENGTH}
        minCharsHint={`Ingresa al menos ${CARGO_TYPE_SEARCH_MIN_LENGTH} caracteres para buscar.`}
        emptyText="No se encontraron tipos de carga."
        error={error}
        createLabel={canCreate ? 'Nuevo tipo de carga' : undefined}
        onCreateClick={canCreate ? () => setModalOpen(true) : undefined}
      />
      {modalOpen && (
        <CargoTypeCreateModal
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
