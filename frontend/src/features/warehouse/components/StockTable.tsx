import type { WarehouseProductResponse } from '../../../api'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatQuantity } from '../../../shared/utils/formatters'
import { StockLevel } from './StockLevel'

interface StockTableProps {
  data: WarehouseProductResponse[]
  page: number
  size: number
  total: number
  totalPages: number
  isLoading: boolean
  isFetching: boolean
  isError: boolean
  errorMessage?: string
  onRetry: () => void
  onPageChange: (page: number) => void
  onRowClick: (product: WarehouseProductResponse) => void
  /** Hay filtros aplicados: cambia el copy del estado vacío. */
  hasActiveFilters: boolean
}

/** Marca y número de parte, ambos opcionales en el contrato. */
function formatIdentificationLabel(product: WarehouseProductResponse): string {
  const parts = [product.brand, product.partNumber].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : '—'
}

/** Tabla de existencias: cada fila lleva al detalle del producto con su kardex. */
export function StockTable({
  data,
  page,
  size,
  total,
  totalPages,
  isLoading,
  isFetching,
  isError,
  errorMessage,
  onRetry,
  onPageChange,
  onRowClick,
  hasActiveFilters,
}: StockTableProps) {
  const columns: Column<WarehouseProductResponse>[] = [
    {
      key: 'code',
      header: 'Código',
      render: (product) => (
        <span className="font-medium tabular-nums text-fg">{product.code}</span>
      ),
    },
    {
      key: 'name',
      header: 'Producto',
      render: (product) => (
        <div>
          <p className="font-medium text-fg">{product.name}</p>
          <p className="text-xs text-fg-muted">{formatIdentificationLabel(product)}</p>
        </div>
      ),
    },
    {
      key: 'category',
      header: 'Categoría',
      render: (product) => product.category.name,
    },
    {
      key: 'stock',
      header: 'Stock actual',
      align: 'right',
      render: (product) => (
        <StockLevel
          stock={product.stock}
          unitCode={product.unitOfMeasure.code}
          low={product.lowStock}
        />
      ),
    },
    {
      key: 'minStock',
      header: 'Mínimo',
      align: 'right',
      render: (product) => (
        <span className="tabular-nums text-fg-muted">
          {formatQuantity(product.minStock)} {product.unitOfMeasure.code}
        </span>
      ),
    },
  ]

  return (
    <DataTable
      columns={columns}
      data={data}
      keyExtractor={(product) => product.id}
      page={page}
      size={size}
      total={total}
      totalPages={totalPages}
      onPageChange={onPageChange}
      isLoading={isLoading}
      isFetching={isFetching}
      isError={isError}
      errorMessage={errorMessage}
      onRetry={onRetry}
      onRowClick={onRowClick}
      rowLabel={(product) => `Ver el producto ${product.code}: ${product.name}`}
      caption="Existencias del almacén"
      emptyTitle={hasActiveFilters ? 'No se encontraron productos' : 'Aún no hay productos'}
      emptyDescription={
        hasActiveFilters
          ? 'Prueba ajustar los filtros de búsqueda.'
          : 'El inventario se puebla con el corte inicial y las entradas de compra.'
      }
    />
  )
}
