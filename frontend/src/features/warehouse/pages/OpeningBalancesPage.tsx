import { useState } from 'react'
import { toast } from 'sonner'
import type { WarehouseProductSummary } from '../../../api'
import { useAuth } from '../../../shared/auth/AuthContext'
import { OPENING_BALANCE_REGISTER_ROLES } from '../../../shared/auth/moduleRoles'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { useLastGoodPage } from '../../../shared/hooks/useLastGoodPage'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { formatQuantity } from '../../../shared/utils/formatters'
import { OpeningBalanceForm } from '../components/OpeningBalanceForm'
import { OpeningBalancesTable } from '../components/OpeningBalancesTable'
import { WarehouseProductField } from '../components/WarehouseProductField'
import { useWarehouseOpeningBalancesList } from '../hooks/useWarehouseOpeningBalancesList'
import { Card } from '../../../shared/ui/Card'

const PAGE_SIZE = 10

/**
 * Corte inicial: con cuánto arranca cada producto en el sistema. Form de carga
 * arriba y listado de lo ya registrado abajo, en una sola pantalla, porque son las
 * dos mitades de la misma tarea: cargar el inventario de arranque y controlar qué
 * falta.
 *
 * El filtro por producto responde la pregunta más frecuente durante esa carga
 * ("¿este ya lo registré?"), que si no se contesta acá solo se responde al enviar
 * y recibir el 409.
 */
export function OpeningBalancesPage() {
  const { user } = useAuth()
  const canRegister = !!user && OPENING_BALANCE_REGISTER_ROLES.includes(user.role)
  const [filterProduct, setFilterProduct] = useState<WarehouseProductSummary | null>(null)
  const [page, setPage] = useState(0)

  const { data, isLoading, isFetching, isError, error, refetch } =
    useWarehouseOpeningBalancesList({
      page,
      size: PAGE_SIZE,
      productId: filterProduct?.id,
    })

  const shownPage = useLastGoodPage(data)

  function handleFilterChange(product: WarehouseProductSummary | null) {
    setFilterProduct(product)
    setPage(0) // cualquier cambio de filtro reinicia a la primera página
  }

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Corte inicial"
        description="Con cuánto arranca cada producto · se registra una sola vez por producto y no se puede editar ni anular."
        divider
      />

      {canRegister ? (
        <Card as="section" aria-labelledby="opening-balance-form-heading">
          <h2
            id="opening-balance-form-heading"
            className="mb-4 text-sm font-semibold text-fg"
          >
            Registrar corte inicial
          </h2>
          <OpeningBalanceForm
            onCreated={(balance) => {
              toast.success(
                `Corte inicial de ${balance.product.name} registrado: ${formatQuantity(balance.quantity)} ${balance.product.unitCode}.`,
              )
              // La carga es en tanda: el listado vuelve al inicio (donde entra el
              // registro nuevo) y el filtro se libera para no esconderlo.
              setFilterProduct(null)
              setPage(0)
            }}
          />
        </Card>
      ) : (
        // Un espacio vacío se leería como una pantalla rota: se dice por qué no
        // está el formulario. La autoridad real es el 403 del backend.
        <p className="rounded-xl border border-border bg-surface-subtle px-5 py-4 text-sm text-fg-body">
          El corte inicial lo registra un administrador. Acá puedes consultar las
          aperturas ya cargadas.
        </p>
      )}

      <section aria-labelledby="opening-balances-list-heading" className="space-y-4">
        <h2
          id="opening-balances-list-heading"
          className="text-sm font-semibold text-fg"
        >
          Cortes iniciales registrados
        </h2>

        <div className="sm:max-w-sm">
          <WarehouseProductField
            id="opening-balances-filter-product"
            ariaLabel="Filtrar por producto"
            selected={filterProduct}
            onSelectedChange={handleFilterChange}
          />
        </div>

        <OpeningBalancesTable
          data={shownPage?.content ?? []}
          page={shownPage?.page ?? page}
          size={PAGE_SIZE}
          total={shownPage?.totalElements ?? 0}
          totalPages={shownPage?.totalPages ?? 0}
          isLoading={isLoading}
          isFetching={isFetching}
          isError={isError}
          errorMessage={getApiErrorMessage(error, 'No se pudieron cargar los cortes iniciales.')}
          onRetry={() => refetch()}
          onPageChange={setPage}
          hasActiveFilter={!!filterProduct}
          canRegister={canRegister}
        />
      </section>
    </div>
  )
}
