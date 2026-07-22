import { useState } from 'react'
import { useFormContext } from 'react-hook-form'
import type { WarehouseProductResponse } from '../../../api'
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
import type { PurchaseInvoiceFormInput } from '../schemas/purchase-invoice.schema'
import { ProductFormModal } from './ProductFormModal'

const SEARCH_DEBOUNCE_MS = 300

interface EntryProductFieldProps {
  /** Índice de la fila: cada ítem tiene su propio combobox e id. */
  index: number
  selected: WarehouseProductResponse | null
  onSelectedChange: (product: WarehouseProductResponse | null) => void
  error?: string
}

function toOption(product: WarehouseProductResponse): ComboboxOption {
  return {
    id: product.id,
    label: product.name,
    sublabel: `${product.code} · ${product.unitOfMeasure.code}`,
  }
}

/**
 * Producto de una fila de la entrada: búsqueda async (minLength 3) y alta al
 * vuelo. El `productId` vive en el form; el objeto completo lo administra la
 * tabla de ítems, que necesita el código y la unidad para mostrarlos en la fila.
 */
export function EntryProductField({
  index,
  selected,
  onSelectedChange,
  error,
}: EntryProductFieldProps) {
  const { setValue, trigger } = useFormContext<PurchaseInvoiceFormInput>()
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const debouncedQuery = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isFetching, isError } = useWarehouseProductsSearch(debouncedQuery)

  const options = (data?.content ?? []).map(toOption)

  function applyProduct(product: WarehouseProductResponse) {
    onSelectedChange(product)
    setValue(`items.${index}.productId`, product.id, {
      shouldValidate: true,
      shouldTouch: true,
    })
  }

  function handleSelect(option: ComboboxOption) {
    const product = data?.content.find((item) => item.id === option.id)
    if (product) applyProduct(product)
  }

  function handleClear() {
    onSelectedChange(null)
    // 0 y no `undefined`: el schema exige un entero positivo, y un campo borrado
    // del form no dispararía el mensaje "Selecciona un producto".
    setValue(`items.${index}.productId`, 0, { shouldValidate: true })
  }

  const queryLength = query.trim().length
  const canCreate =
    queryLength >= PRODUCT_NAME_MIN_LENGTH && queryLength <= PRODUCT_NAME_MAX_LENGTH

  return (
    <>
      <Combobox
        id={`entry-item-product-${index}`}
        ariaLabel={`Producto del ítem ${index + 1}`}
        placeholder="Busca por código, nombre o marca…"
        options={options}
        selected={selected ? toOption(selected) : null}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={handleClear}
        onBlur={() => trigger(`items.${index}.productId`)}
        loading={isFetching}
        minChars={PRODUCT_SEARCH_MIN_LENGTH}
        minCharsHint={`Ingresa al menos ${PRODUCT_SEARCH_MIN_LENGTH} caracteres para buscar.`}
        emptyText="No se encontraron productos."
        error={error}
        createLabel={canCreate ? 'Nuevo producto' : undefined}
        onCreateClick={canCreate ? () => setModalOpen(true) : undefined}
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-amber-700">
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
          onCreated={applyProduct}
        />
      )}
    </>
  )
}
