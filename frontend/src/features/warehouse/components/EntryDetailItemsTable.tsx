import type { WarehouseInvoiceItemResponse } from '../../../api'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { formatCurrency, formatQuantity } from '../../../shared/utils/formatters'

interface EntryDetailItemsTableProps {
  items: WarehouseInvoiceItemResponse[]
  /** Total de la factura, tal como lo devuelve el backend (el front no lo recalcula). */
  total: number
  currencyCode: string
  /** Navega a la ficha del producto al hacer clic en su fila (patrón de Existencias). */
  onProductClick: (item: WarehouseInvoiceItemResponse) => void
}

/**
 * Ítems de la entrada en modo lectura. Reusa `DataTable` para que cada fila
 * navegue a la ficha del producto con la misma interacción y accesibilidad que
 * Existencias (`role="button"`, teclado, `aria-label`). No pagina (la factura
 * trae todos sus ítems: `totalPages` = 1 oculta el paginador) y el total va como
 * línea aparte, porque la tabla genérica no contempla una fila de total.
 *
 * `subtotal` y `total` se muestran tal como vienen del backend: el front no los
 * recalcula (el backend los deriva y es la única fuente de verdad).
 */
export function EntryDetailItemsTable({
  items,
  total,
  currencyCode,
  onProductClick,
}: EntryDetailItemsTableProps) {
  const columns: Column<WarehouseInvoiceItemResponse>[] = [
    {
      key: 'product',
      header: 'Producto',
      render: (item) => (
        <div>
          <p className="font-medium text-slate-900">{item.product.name}</p>
          <p className="text-xs text-slate-500">
            {item.product.code ? `${item.product.code} · ` : ''}
            {item.product.unitCode}
          </p>
        </div>
      ),
    },
    {
      key: 'quantity',
      header: 'Cantidad',
      align: 'right',
      render: (item) => <span className="tabular-nums">{formatQuantity(item.quantity)}</span>,
    },
    {
      key: 'unitPrice',
      header: 'Precio unit.',
      align: 'right',
      render: (item) => (
        <span className="tabular-nums">{formatCurrency(item.unitPrice, currencyCode)}</span>
      ),
    },
    {
      key: 'subtotal',
      header: 'Subtotal',
      align: 'right',
      render: (item) => (
        <span className="font-medium tabular-nums text-slate-900">
          {formatCurrency(item.subtotal, currencyCode)}
        </span>
      ),
    },
  ]

  return (
    <section aria-labelledby="entry-items-heading" className="space-y-3">
      <h2 id="entry-items-heading" className="text-lg font-semibold text-slate-900">
        Ítems
      </h2>
      <DataTable
        columns={columns}
        data={items}
        keyExtractor={(item) => item.id}
        onRowClick={onProductClick}
        rowLabel={(item) => `Ver la ficha de ${item.product.name}`}
        page={0}
        size={items.length}
        total={items.length}
        totalPages={1}
        onPageChange={() => {}}
        caption="Ítems de la factura de compra"
      />
      <div className="flex justify-end px-1">
        <p className="text-sm text-slate-600">
          Total de la factura:{' '}
          <span className="font-semibold tabular-nums text-slate-900">
            {formatCurrency(total, currencyCode)}
          </span>
        </p>
      </div>
    </section>
  )
}
