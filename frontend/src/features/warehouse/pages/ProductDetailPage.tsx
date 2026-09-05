import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Pencil } from 'lucide-react'
import { BackLink } from '../../../shared/ui/BackLink'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { useLastGoodPage } from '../../../shared/hooks/useLastGoodPage'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { KardexTable } from '../components/KardexTable'
import { ProductFormModal } from '../components/ProductFormModal'
import { ProductInactiveBadge, ProductInfoCards } from '../components/ProductInfoCards'
import { useWarehouseProduct } from '../hooks/useWarehouseProduct'
import { useWarehouseProductKardex } from '../hooks/useWarehouseProductKardex'
import { Card } from '../../../shared/ui/Card'

const KARDEX_PAGE_SIZE = 10
const STOCK_LIST_PATH = '/cotizaciones/almacen'

/**
 * Detalle de un producto: su ficha de catálogo, las existencias y el kardex.
 *
 * Las dos consultas son independientes: si el kardex falla la ficha sigue en
 * pantalla, y al revés. El alta de productos no vive acá (es al vuelo desde el
 * form que la necesita), así que esta pantalla solo edita.
 */
export function ProductDetailPage() {
  const { id } = useParams()
  const productId = Number(id)
  const [isEditOpen, setEditOpen] = useState(false)
  const [kardexPage, setKardexPage] = useState(0)

  const product = useWarehouseProduct(productId)
  // Un id no numérico igual entra a la ruta (el patrón no lo distingue): se
  // resuelve acá como "no encontrado" en vez de dispararle una request al backend.
  const invalidId = !Number.isInteger(productId) || productId <= 0
  const productMissing = isNotFoundError(product.error)

  const kardex = useWarehouseProductKardex({
    productId,
    page: kardexPage,
    size: KARDEX_PAGE_SIZE,
    // Sin producto no hay kardex que mostrar: pedirlo sería un segundo 404 que
    // la pantalla descarta igual.
    enabled: !invalidId && !productMissing,
  })
  // Anular una factura o un retiro borra movimientos, así que el kardex puede
  // achicarse: sin retener la última página buena, quien esté parado en la última
  // se queda con la tabla vacía y sin paginador para volver.
  const kardexPageData = useLastGoodPage(kardex.data)

  if (invalidId || productMissing) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={STOCK_LIST_PATH}>Volver a existencias</BackLink>
        <EmptyState
          title="No se encontró el producto"
          description="Puede que se haya dado de baja o que el enlace esté mal."
          action={
            <Link
              to={STOCK_LIST_PATH}
              className="inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
            >
              Ir a existencias
            </Link>
          }
        />
      </div>
    )
  }

  if (product.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando producto" className="text-accent" />
      </div>
    )
  }

  if (product.isError || !product.data) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={STOCK_LIST_PATH}>Volver a existencias</BackLink>
        <div role="alert" className="flex flex-col items-center justify-center py-16 text-center">
          <p className="text-sm font-medium text-fg-body">
            {getApiErrorMessage(product.error, 'No se pudo cargar el producto.')}
          </p>
          <button
            type="button"
            onClick={() => product.refetch()}
            className="mt-4 inline-flex items-center rounded-lg border border-border-strong bg-surface px-4 py-2 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = product.data

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <BackLink to={STOCK_LIST_PATH}>Volver a existencias</BackLink>

      <PageHeader
        title={data.name}
        description={`${data.code} · ${data.category.name}`}
        divider
        action={
          <button
            type="button"
            onClick={() => setEditOpen(true)}
            className="inline-flex items-center gap-2 rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-on-solid shadow-sm hover:bg-accent-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-canvas"
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
            Editar
          </button>
        }
      />

      {!data.isActive && <ProductInactiveBadge />}

      <ProductInfoCards product={data} />

      {data.observations && (
        <Card as="section" padding="md" aria-labelledby="product-observations-heading">
          <h2 id="product-observations-heading" className="text-sm font-semibold text-fg">
            Observaciones
          </h2>
          <p className="mt-2 whitespace-pre-line text-sm text-fg-body">{data.observations}</p>
        </Card>
      )}

      <section aria-labelledby="product-kardex-heading" className="space-y-3">
        <h2 id="product-kardex-heading" className="text-lg font-semibold text-fg">
          Kardex
        </h2>
        <KardexTable
          data={kardexPageData?.content ?? []}
          page={kardexPageData?.page ?? kardexPage}
          size={KARDEX_PAGE_SIZE}
          total={kardexPageData?.totalElements ?? 0}
          totalPages={kardexPageData?.totalPages ?? 0}
          unitCode={data.unitOfMeasure.code}
          isLoading={kardex.isLoading}
          isFetching={kardex.isFetching}
          isError={kardex.isError}
          errorMessage={getApiErrorMessage(kardex.error, 'No se pudo cargar el kardex.')}
          onRetry={() => kardex.refetch()}
          onPageChange={setKardexPage}
        />
      </section>

      <ProductFormModal
        mode="edit"
        isOpen={isEditOpen}
        onClose={() => setEditOpen(false)}
        product={data}
        onReloadRequested={() => {
          product.refetch()
          setEditOpen(false)
        }}
      />
    </div>
  )
}
