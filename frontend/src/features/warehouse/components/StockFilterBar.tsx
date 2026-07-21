import { Search, X } from 'lucide-react'
import type { WarehouseProductCategoryResponse } from '../../../api'
import { cn } from '../../../shared/utils/cn'
import { SEARCH_MIN_LENGTH } from '../hooks/useWarehouseProductsList'
import { SEARCH_MAX_LENGTH, type StockFilters } from '../schemas/stock-filters.schema'

interface StockFilterBarProps {
  value: StockFilters
  onChange: (next: StockFilters) => void
  categories: WarehouseProductCategoryResponse[]
  categoriesLoading: boolean
  /** El catálogo no cargó: el select queda sin opciones y hay que decirlo. */
  categoriesError: boolean
}

const inputClasses =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Barra de filtros de Existencias: búsqueda libre + categoría. El debounce de
 * la búsqueda lo aplica la página (este componente solo refleja y propaga).
 *
 * El corte "solo stock bajo" se activa desde el tile del strip, pero cuando
 * está aplicado aparece acá como chip removible: si no, el filtro quedaría
 * invisible desde donde el usuario mira los filtros (y sin forma de quitarlo
 * cuando el strip está en error).
 */
export function StockFilterBar({
  value,
  onChange,
  categories,
  categoriesLoading,
  categoriesError,
}: StockFilterBarProps) {
  function set<K extends keyof StockFilters>(key: K, fieldValue: StockFilters[K]) {
    onChange({ ...value, [key]: fieldValue })
  }

  const qLength = value.q.trim().length
  const showSearchHint = qLength > 0 && qLength < SEARCH_MIN_LENGTH

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {/* Búsqueda libre */}
        <div className="sm:col-span-2">
          <label
            htmlFor="warehouse-q"
            className="mb-1.5 block text-sm font-medium text-slate-700"
          >
            Buscar
          </label>
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
              aria-hidden="true"
            />
            <input
              id="warehouse-q"
              type="search"
              value={value.q}
              maxLength={SEARCH_MAX_LENGTH}
              onChange={(event) => set('q', event.target.value)}
              placeholder="Código, producto, marca, número de parte…"
              aria-describedby={showSearchHint ? 'warehouse-q-hint' : undefined}
              className={cn(inputClasses, 'pl-9 pr-3 placeholder:text-slate-400')}
            />
          </div>
          {showSearchHint && (
            <p id="warehouse-q-hint" className="mt-1 text-xs text-slate-500">
              Ingresa al menos {SEARCH_MIN_LENGTH} caracteres para buscar.
            </p>
          )}
        </div>

        {/* Categoría */}
        <div>
          <label
            htmlFor="warehouse-category"
            className="mb-1.5 block text-sm font-medium text-slate-700"
          >
            Categoría
          </label>
          <select
            id="warehouse-category"
            value={value.categoryId ?? ''}
            disabled={categoriesLoading}
            onChange={(event) =>
              set('categoryId', event.target.value ? Number(event.target.value) : undefined)
            }
            aria-describedby={categoriesError ? 'warehouse-category-error' : undefined}
            className={cn(inputClasses, categoriesLoading && 'cursor-not-allowed opacity-60')}
          >
            <option value="">Todas</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
          {categoriesError && (
            <p id="warehouse-category-error" role="alert" className="mt-1 text-xs text-amber-700">
              No se pudieron cargar las categorías.
            </p>
          )}
        </div>
      </div>

      {value.lowOnly && (
        <div className="mt-4 flex items-center gap-2">
          <span className="text-xs font-medium uppercase tracking-wide text-slate-500">
            Filtros aplicados
          </span>
          <button
            type="button"
            onClick={() => set('lowOnly', false)}
            aria-label="Quitar filtro: solo stock bajo"
            className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-medium text-amber-700 hover:bg-amber-200 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            Solo stock bajo
            <X className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        </div>
      )}
    </div>
  )
}
