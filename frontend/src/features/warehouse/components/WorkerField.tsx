import { useState } from 'react'
import type { WorkerResponse } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { WORKER_SEARCH_MIN_LENGTH, useWorkersSearch } from '../hooks/useWorkersSearch'

const SEARCH_DEBOUNCE_MS = 300

interface WorkerFieldProps {
  id: string
  label?: string
  ariaLabel?: string
  /** Trabajador elegido. Vive en el consumidor, que necesita su nombre para el label. */
  selected: WorkerResponse | null
  onSelectedChange: (worker: WorkerResponse | null) => void
  onBlur?: () => void
  error?: string
}

function toOption(worker: WorkerResponse): ComboboxOption {
  return {
    id: worker.id,
    label: worker.fullName,
    // El cargo es opcional: sin él, la opción va sin sublabel.
    sublabel: worker.position ?? undefined,
  }
}

/**
 * Selección de trabajador ("quién recibe") con búsqueda async (minLength 3,
 * multi-palabra). NO ofrece crear al vuelo (RN-WH9): los trabajadores se dan de
 * alta en operaciones, no desde almacén. Componente controlado: el consumidor
 * administra el `receivedByWorkerId`.
 */
export function WorkerField({
  id,
  label,
  ariaLabel,
  selected,
  onSelectedChange,
  onBlur,
  error,
}: WorkerFieldProps) {
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isFetching, isError } = useWorkersSearch(debouncedQuery)

  const options = (data ?? []).map(toOption)

  function handleSelect(option: ComboboxOption) {
    const worker = data?.find((item) => item.id === option.id)
    if (worker) onSelectedChange(worker)
  }

  return (
    <div>
      <Combobox
        id={id}
        label={label}
        ariaLabel={ariaLabel}
        placeholder="Busca por nombre o apellido…"
        options={options}
        selected={selected ? toOption(selected) : null}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={() => onSelectedChange(null)}
        onBlur={onBlur}
        loading={isFetching}
        minChars={WORKER_SEARCH_MIN_LENGTH}
        minCharsHint={`Ingresa al menos ${WORKER_SEARCH_MIN_LENGTH} caracteres para buscar.`}
        emptyText="No se encontraron trabajadores."
        error={error}
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-amber-700">
          No se pudieron buscar trabajadores. Intenta de nuevo.
        </p>
      )}
    </div>
  )
}
