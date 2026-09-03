import type { WarehouseProductResponse } from '../../../api'
import { Badge } from '../../../shared/ui/Badge'
import { formatDate, formatQuantity } from '../../../shared/utils/formatters'
import { StockLevel } from './StockLevel'
import { Card } from '../../../shared/ui/Card'

interface ProductInfoCardsProps {
  product: WarehouseProductResponse
}


function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-0.5 text-sm text-slate-900">{value}</dd>
    </div>
  )
}

/**
 * Tarjetas del detalle: existencias, ficha del catálogo y características. Van
 * juntas en un archivo porque las tres describen al mismo producto y ninguna se
 * usa fuera de esta pantalla; separarlas sería un archivo por bloque visual.
 */
export function ProductInfoCards({ product }: ProductInfoCardsProps) {
  const attributes = Object.entries(product.attributes)

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <Card as="section" padding="md" aria-labelledby="product-stock-heading">
        <h2 id="product-stock-heading" className="text-sm font-semibold text-slate-900">
          Existencias
        </h2>
        <p className="mt-3">
          <StockLevel
            stock={product.stock}
            unitCode={product.unitOfMeasure.code}
            low={product.lowStock}
          />
        </p>
        <p className="mt-2 text-sm text-slate-500">
          Mínimo {formatQuantity(product.minStock)} {product.unitOfMeasure.code}
        </p>
      </Card>

      <Card as="section" padding="md" aria-labelledby="product-info-heading">
        <h2 id="product-info-heading" className="text-sm font-semibold text-slate-900">
          Ficha
        </h2>
        <dl className="mt-3 space-y-3">
          <Field label="Categoría" value={product.category.name} />
          <Field
            label="Unidad de medida"
            value={`${product.unitOfMeasure.code} · ${product.unitOfMeasure.name}`}
          />
          <Field label="Marca" value={product.brand ?? '—'} />
          <Field label="Número de parte" value={product.partNumber ?? '—'} />
          <Field
            label="Registrado"
            value={`${product.createdBy.fullName} · ${formatDate(product.createdAt)}`}
          />
        </dl>
      </Card>

      <Card as="section" padding="md" aria-labelledby="product-attributes-heading">
        <h2 id="product-attributes-heading" className="text-sm font-semibold text-slate-900">
          Características
        </h2>
        {attributes.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">Sin características registradas.</p>
        ) : (
          <dl className="mt-3 space-y-3">
            {attributes.map(([key, value]) => (
              <Field key={key} label={key} value={value} />
            ))}
          </dl>
        )}
      </Card>
    </div>
  )
}

/** Distintivo del producto dado de baja. La baja todavía no se hace desde la UI,
 * pero un producto inactivo puede llegar por URL o desde el kardex. */
export function ProductInactiveBadge() {
  return <Badge variant="slate">Inactivo</Badge>
}
