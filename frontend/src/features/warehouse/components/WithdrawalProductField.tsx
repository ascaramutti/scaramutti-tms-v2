import { useState } from 'react'
import type { WarehouseProductResponse, WarehouseProductSummary } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import {
  PRODUCT_SEARCH_MIN_LENGTH,
  useWarehouseProductsSearch,
} from '../hooks/useWarehouseProductsSearch'

const SEARCH_DEBOUNCE_MS = 300

interface WithdrawalProductFieldProps {
  id: string
  label?: string
  ariaLabel?: string
  /** Producto elegido. Vive en el consumidor, que necesita su código y unidad. */
  selected: WarehouseProductSummary | null
  onSelectedChange: (product: WarehouseProductSummary | null) => void
  onBlur?: () => void
  error?: string
}

/** Baja el producto completo de la búsqueda al summary que la fila/campo necesita. */
function productResponseToSummary(product: WarehouseProductResponse): WarehouseProductSummary {
  return {
    id: product.id,
    code: product.code,
    name: product.name,
    unitCode: product.unitOfMeasure.code,
  }
}

function toOption(product: WarehouseProductSummary): ComboboxOption {
  return {
    id: product.id,
    label: product.name,
    // `code` es opcional en el summary: sin él, la unidad sola hace de sublabel.
    sublabel: product.code ? `${product.code} · ${product.unitCode}` : product.unitCode,
  }
}

/**
 * Selección de producto con búsqueda async (minLength 3). A diferencia del combobox
 * de producto de la entrada, NO ofrece crear al vuelo: un retiro solo descuenta
 * stock de un producto que ya existe. Componente controlado: el `productId` lo
 * administra el consumidor (form o barra de filtros).
 */
export function WithdrawalProductField({
  id,
  label,
  ariaLabel,
  selected,
  onSelectedChange,
  onBlur,
  error,
}: WithdrawalProductFieldProps) {
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isFetching, isError } = useWarehouseProductsSearch(debouncedQuery)

  const options = (data?.content ?? []).map((product) =>
    toOption(productResponseToSummary(product)),
  )

  function handleSelect(option: ComboboxOption) {
    const product = data?.content.find((item) => item.id === option.id)
    if (product) onSelectedChange(productResponseToSummary(product))
  }

  return (
    <div>
      <Combobox
        id={id}
        label={label}
        ariaLabel={ariaLabel}
        placeholder="Busca por código, nombre o marca…"
        options={options}
        selected={selected ? toOption(selected) : null}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={() => onSelectedChange(null)}
        onBlur={onBlur}
        loading={isFetching}
        minChars={PRODUCT_SEARCH_MIN_LENGTH}
        minCharsHint={`Ingresa al menos ${PRODUCT_SEARCH_MIN_LENGTH} caracteres para buscar.`}
        emptyText="No se encontraron productos."
        error={error}
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-amber-700">
          No se pudieron buscar productos. Intenta de nuevo.
        </p>
      )}
    </div>
  )
}
