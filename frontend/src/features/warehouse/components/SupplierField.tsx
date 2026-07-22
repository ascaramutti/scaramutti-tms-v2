import { useState } from 'react'
import { useFormContext } from 'react-hook-form'
import { toast } from 'sonner'
import type { WarehouseSupplierResponse } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import {
  SUPPLIER_SEARCH_MIN_LENGTH,
  useWarehouseSuppliersSearch,
} from '../hooks/useWarehouseSuppliersSearch'
import {
  SUPPLIER_NAME_MAX_LENGTH,
  SUPPLIER_NAME_MIN_LENGTH,
} from '../schemas/supplier.schema'
import type { PurchaseInvoiceFormInput } from '../schemas/purchase-invoice.schema'
import { SupplierCreateModal } from './SupplierCreateModal'

const SEARCH_DEBOUNCE_MS = 300

interface SupplierFieldProps {
  /** Proveedor elegido. Vive en el form padre, que necesita su nombre para el label. */
  selected: WarehouseSupplierResponse | null
  onSelectedChange: (supplier: WarehouseSupplierResponse | null) => void
}

function toOption(supplier: WarehouseSupplierResponse): ComboboxOption {
  return {
    id: supplier.id,
    label: supplier.name,
    // El RUC es opcional en proveedores: sin él, la opción va sin sublabel en
    // lugar de mostrar "RUC" a secas.
    sublabel: supplier.ruc ? `RUC ${supplier.ruc}` : undefined,
  }
}

/**
 * Selección de proveedor con búsqueda async (minLength 3) y creación al vuelo,
 * el estándar del proyecto para catálogos en forms. El `supplierId` vive en el
 * form (es lo que se valida y se envía); el objeto completo lo administra el
 * padre, que lo necesita para pintar el nombre y el RUC.
 */
export function SupplierField({ selected, onSelectedChange }: SupplierFieldProps) {
  const {
    setValue,
    trigger,
    formState: { errors },
  } = useFormContext<PurchaseInvoiceFormInput>()
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const debouncedQuery = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isFetching, isError } = useWarehouseSuppliersSearch(debouncedQuery)

  const options = (data?.content ?? []).map(toOption)

  function applySupplier(supplier: WarehouseSupplierResponse) {
    onSelectedChange(supplier)
    setValue('supplierId', supplier.id, { shouldValidate: true, shouldTouch: true })
  }

  function handleSelect(option: ComboboxOption) {
    const supplier = data?.content.find((item) => item.id === option.id)
    if (supplier) applySupplier(supplier)
  }

  function handleClear() {
    onSelectedChange(null)
    // 0 y no `undefined`: el schema exige un entero positivo, y un campo borrado
    // del form no dispararía el mensaje "Selecciona el proveedor".
    setValue('supplierId', 0, { shouldValidate: true })
  }

  // El "+ Crear" solo aparece con un nombre que el contrato acepta: ofrecerlo
  // fuera de rango llevaría derecho a un 400.
  const queryLength = query.trim().length
  const canCreate =
    queryLength >= SUPPLIER_NAME_MIN_LENGTH && queryLength <= SUPPLIER_NAME_MAX_LENGTH

  return (
    <div>
      <Combobox
        id="entry-supplier"
        label="Proveedor"
        placeholder="Busca por razón social o RUC…"
        options={options}
        selected={selected ? toOption(selected) : null}
        onQueryChange={setQuery}
        onSelect={handleSelect}
        onClear={handleClear}
        onBlur={() => trigger('supplierId')}
        loading={isFetching}
        minChars={SUPPLIER_SEARCH_MIN_LENGTH}
        minCharsHint={`Ingresa al menos ${SUPPLIER_SEARCH_MIN_LENGTH} caracteres para buscar.`}
        emptyText="No se encontraron proveedores."
        error={errors.supplierId?.message}
        createLabel={canCreate ? 'Nuevo proveedor' : undefined}
        onCreateClick={canCreate ? () => setModalOpen(true) : undefined}
      />
      {isError && (
        <p role="alert" className="mt-1 text-xs text-amber-700">
          No se pudieron buscar proveedores. Intenta de nuevo.
        </p>
      )}
      {modalOpen && (
        <SupplierCreateModal
          initialName={query}
          onClose={() => setModalOpen(false)}
          onCreated={(supplier) => {
            // Se aplica el objeto que devuelve el POST en vez de esperar el refetch:
            // el proveedor recién creado podría no matchear el texto tipeado.
            applySupplier(supplier)
            setModalOpen(false)
            toast.success(`Proveedor "${supplier.name}" creado.`)
          }}
        />
      )}
    </div>
  )
}
