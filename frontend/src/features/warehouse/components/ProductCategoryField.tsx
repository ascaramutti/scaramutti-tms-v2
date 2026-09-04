import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import type { WarehouseProductCategoryResponse } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { useCreateWarehouseProductCategory } from '../hooks/useCreateWarehouseProductCategory'

/** Límites de `name` en el contrato de categorías: fuera de rango, el POST vuelve 400. */
const CATEGORY_NAME_MIN_LENGTH = 3
const CATEGORY_NAME_MAX_LENGTH = 100

interface ProductCategoryFieldProps {
  categories: WarehouseProductCategoryResponse[]
  categoriesLoading: boolean
  /** El catálogo no cargó: la selección actual no se puede mostrar y hay que decirlo. */
  categoriesError: boolean
  value: number | null
  onChange: (categoryId: number | null) => void
  onBlur?: () => void
  error?: string
  disabled?: boolean
}

/**
 * Selección de categoría con búsqueda y creación al vuelo (estándar del proyecto
 * para catálogos en forms). El catálogo es chico y llega completo en una sola
 * llamada, así que el filtrado es local: el `Combobox` espera opciones ya
 * filtradas y este componente se las da.
 */
export function ProductCategoryField({
  categories,
  categoriesLoading,
  categoriesError,
  value,
  onChange,
  onBlur,
  error,
  disabled,
}: ProductCategoryFieldProps) {
  const [query, setQuery] = useState('')
  const createCategory = useCreateWarehouseProductCategory()

  const options = useMemo<ComboboxOption[]>(() => {
    const normalizedQuery = query.trim().toLowerCase()
    return categories
      .filter((category) => category.name.toLowerCase().includes(normalizedQuery))
      .map((category) => ({ id: category.id, label: category.name }))
  }, [categories, query])

  const selectedCategory = categories.find((category) => category.id === value)
  const selected: ComboboxOption | null = selectedCategory
    ? { id: selectedCategory.id, label: selectedCategory.name }
    : null

  function handleCreate(name: string) {
    createCategory.mutate(name, {
      onSuccess: (created) => {
        onChange(created.id)
        setQuery('')
        toast.success(`Categoría "${created.name}" creada.`)
      },
      onError: (createError) => {
        toast.error(
          getApiErrorMessage(createError, 'No se pudo crear la categoría. Intenta de nuevo.'),
        )
      },
    })
  }

  // El "+ Crear" solo aparece con un nombre que el contrato acepta: ofrecerlo fuera
  // de rango llevaría derecho a un 400 que el backend nunca iba a aceptar.
  const queryLength = query.trim().length
  const canCreate =
    queryLength >= CATEGORY_NAME_MIN_LENGTH && queryLength <= CATEGORY_NAME_MAX_LENGTH

  // Sin catálogo no hay con qué resolver el nombre de la categoría guardada, así que
  // el campo se ve vacío aunque el producto la tenga: sin el aviso, parece borrada.
  return (
    <div>
      <Combobox
        id="product-category"
        label="Categoría"
        placeholder="Buscar categoría…"
        options={options}
        selected={selected}
        onQueryChange={setQuery}
        // Resuelve la categoría de la lista (su id es numérico); el id del combobox
        // se amplió a string para claves compuestas de otros catálogos.
        onSelect={(option) => {
          const category = categories.find((item) => item.id === option.id)
          if (category) onChange(category.id)
        }}
        onClear={() => onChange(null)}
        onBlur={onBlur}
        loading={categoriesLoading || createCategory.isPending}
        emptyText="No se encontraron categorías."
        error={error}
        createLabel={canCreate ? 'Crear categoría' : undefined}
        onCreateClick={canCreate ? handleCreate : undefined}
        disabled={disabled}
      />
      {categoriesError && (
        <p role="alert" className="mt-1 text-xs text-warning">
          No se pudieron cargar las categorías. La del producto se conserva al guardar.
        </p>
      )}
    </div>
  )
}
