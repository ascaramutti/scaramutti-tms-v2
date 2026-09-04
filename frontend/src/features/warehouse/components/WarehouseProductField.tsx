import { useState } from 'react'
import type { WarehouseProductResponse, WarehouseProductSummary } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import {
  PRODUCT_SEARCH_MIN_LENGTH,
  useWarehouseProductsSearch,
} from '../hooks/useWarehouseProductsSearch'
import {
  PRODUCT_NAME_MAX_LENGTH,
  PRODUCT_NAME_MIN_LENGTH,
} from '../schemas/product.schema'
import { ProductFormModal } from './ProductFormModal'

const SEARCH_DEBOUNCE_MS = 300

interface WarehouseProductFieldProps {
  id: string
  label?: string
  ariaLabel?: string
  /** Producto elegido. Vive en el consumidor, que necesita su código y unidad. */
  selected: WarehouseProductSummary | null
  onSelectedChange: (product: WarehouseProductSummary | null) => void
  onBlur?: () => void
  error?: string
  /**
   * Ofrece dar de alta el producto sin salir del form (como en la entrada). Solo
   * para flujos donde incorporar un producto que todavía no está en el sistema es
   * parte del caso de uso; los filtros y los retiros lo dejan apagado.
   */
  allowCreate?: boolean
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
 * Selección de producto con búsqueda async (minLength 3). Componente controlado:
 * el `productId` lo administra el consumidor (form o barra de filtros).
 *
 * Por defecto solo elige productos YA EXISTENTES; con `allowCreate` suma el alta
 * al vuelo (mismo modal que la entrada). Los filtros y el retiro lo dejan apagado:
 * filtrar por algo que todavía no existe no tiene sentido, y un retiro solo puede
 * descontar stock de un producto ya cargado.
 */
export function WarehouseProductField({
  id,
  label,
  ariaLabel,
  selected,
  onSelectedChange,
  onBlur,
  error,
  allowCreate = false,
}: WarehouseProductFieldProps) {
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const debouncedQuery = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isFetching, isError } = useWarehouseProductsSearch(debouncedQuery)

  const options = (data?.content ?? []).map((product) =>
    toOption(productResponseToSummary(product)),
  )

  function handleSelect(option: ComboboxOption) {
    const product = data?.content.find((item) => item.id === option.id)
    if (product) onSelectedChange(productResponseToSummary(product))
  }

  // El alta se ofrece solo si lo tipeado sirve como nombre del producto nuevo: es
  // lo que el modal precarga, y fuera de rango el POST se rechazaría.
  const queryLength = query.trim().length
  const canCreate =
    allowCreate &&
    queryLength >= PRODUCT_NAME_MIN_LENGTH &&
    queryLength <= PRODUCT_NAME_MAX_LENGTH

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
        createLabel={canCreate ? 'Nuevo producto' : undefined}
        onCreateClick={canCreate ? () => setModalOpen(true) : undefined}
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-warning">
          No se pudieron buscar productos. Intenta de nuevo.
        </p>
      )}
      {modalOpen && (
        <ProductFormModal
          mode="create"
          isOpen
          initialName={query}
          onClose={() => setModalOpen(false)}
          // Se aplica el objeto que devuelve el POST en vez de esperar el refetch:
          // el producto nace con stock 0 y podría no matchear el texto tipeado.
          onCreated={(product) => onSelectedChange(productResponseToSummary(product))}
        />
      )}
    </div>
  )
}
