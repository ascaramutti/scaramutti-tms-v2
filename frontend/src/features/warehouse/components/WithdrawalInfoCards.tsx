import { Link } from 'react-router-dom'
import type { WarehouseWithdrawalResponse } from '../../../api'
import { formatDate, formatQuantity } from '../../../shared/utils/formatters'
import { fleetUnitLabel } from '../../../shared/catalogs/fleetUnit'
import { Card } from '../../../shared/ui/Card'

interface WithdrawalInfoCardsProps {
  withdrawal: WarehouseWithdrawalResponse
}


function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-fg-muted">{label}</dt>
      <dd className="mt-0.5 text-sm text-fg">{value}</dd>
    </div>
  )
}

/**
 * Ficha del retiro: qué salió, cuánto, quién lo recibió y a qué unidad de flota se
 * cargó, más el rastro de la última edición si el retiro fue editado alguna vez.
 * El producto linkea a su ficha (desde ahí se ve el kardex con esta salida).
 */
export function WithdrawalInfoCards({ withdrawal }: WithdrawalInfoCardsProps) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card as="section" padding="md" aria-labelledby="withdrawal-info-heading">
        <h2 id="withdrawal-info-heading" className="text-sm font-semibold text-fg">
          Retiro
        </h2>
        <dl className="mt-3 grid grid-cols-2 gap-3">
          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-fg-muted">Producto</dt>
            <dd className="mt-0.5 text-sm">
              <Link
                to={`/cotizaciones/almacen/productos/${withdrawal.product.id}`}
                className="rounded font-medium text-accent hover:text-accent-hover hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
              >
                {withdrawal.product.name}
              </Link>
              {withdrawal.product.code && (
                <span className="block text-xs text-fg-muted">
                  {withdrawal.product.code} · {withdrawal.product.unitCode}
                </span>
              )}
            </dd>
          </div>
          <Field
            label="Cantidad"
            value={`${formatQuantity(withdrawal.quantity)} ${withdrawal.product.unitCode}`}
          />
          <Field
            label="Recibido por"
            value={
              withdrawal.receivedBy.position
                ? `${withdrawal.receivedBy.fullName} · ${withdrawal.receivedBy.position}`
                : withdrawal.receivedBy.fullName
            }
          />
          <Field
            label="Unidad de flota"
            value={withdrawal.fleetUnit ? fleetUnitLabel(withdrawal.fleetUnit) : '—'}
          />
          <Field
            label="Registró"
            value={`${withdrawal.registeredBy.fullName} · ${formatDate(withdrawal.withdrawnAt)}`}
          />
        </dl>
      </Card>

      {withdrawal.lastEdit && (
        <Card as="section" padding="md" aria-labelledby="withdrawal-lastedit-heading">
          <h2 id="withdrawal-lastedit-heading" className="text-sm font-semibold text-fg">
            Última edición
          </h2>
          <dl className="mt-3 space-y-3">
            <Field
              label="Editó"
              value={`${withdrawal.lastEdit.by.fullName} · ${formatDate(withdrawal.lastEdit.at)}`}
            />
            <Field label="Motivo" value={withdrawal.lastEdit.reason} />
          </dl>
        </Card>
      )}
    </div>
  )
}
