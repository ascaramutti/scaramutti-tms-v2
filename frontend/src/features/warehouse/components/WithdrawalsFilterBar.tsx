import { useState } from 'react'
import type {
  FleetUnitResponse,
  WarehouseProductSummary,
  WorkerResponse,
} from '../../../api'
import { fleetUnitToWithdrawalFields } from '../hooks/useCreateWarehouseWithdrawal'
import type { WithdrawalFilters } from '../schemas/withdrawal-filters.schema'
import { FleetUnitField } from '../../../shared/catalogs/FleetUnitField'
import { WarehouseProductField } from './WarehouseProductField'
import { WorkerField } from './WorkerField'
import { Card } from '../../../shared/ui/Card'
import { fieldClasses } from '../../../shared/ui/fieldClasses'
import { cn } from '../../../shared/utils/cn'

/**
 * Aviso cuando el catálogo de flota no carga.
 *
 * La segunda mitad de la frase habla de REGISTRAR, y esta pantalla solo filtra: el
 * texto viene del formulario de alta, donde sí es cierto. Se deja igual por ahora y
 * se exporta para que el test afirme que la pantalla muestra el suyo sin repetir la
 * frase en el esperado, porque clavarla en dos lugares encarecería corregirla.
 */
export const FLEET_UNITS_LOAD_ERROR =
  'No se pudieron cargar las unidades de flota. El retiro se puede registrar sin unidad.'

interface WithdrawalsFilterBarProps {
  value: WithdrawalFilters
  onChange: (next: WithdrawalFilters) => void
}

const inputClasses = cn('w-full', fieldClasses({ density: 'compact' }))

/**
 * Filtros del listado de retiros: producto, trabajador y unidad de flota (los tres
 * comboboxes), estado y rango de fechas de `withdrawnAt`. Los objetos elegidos
 * viven acá (el label los necesita); a la página solo suben los ids/estado.
 *
 * Un rango invertido no se manda al backend: el contrato no define ese 400 para
 * este listado, así que devolvería un resultado vacío indistinguible de "no hay".
 * Se avisa acá y la página omite las fechas hasta que el rango vuelva a tener
 * sentido.
 */
export function WithdrawalsFilterBar({ value, onChange }: WithdrawalsFilterBarProps) {
  const [selectedProduct, setSelectedProduct] = useState<WarehouseProductSummary | null>(null)
  const [selectedWorker, setSelectedWorker] = useState<WorkerResponse | null>(null)
  const [selectedFleetUnit, setSelectedFleetUnit] = useState<FleetUnitResponse | null>(null)

  const invalidRange = !!value.dateFrom && !!value.dateTo && value.dateFrom > value.dateTo

  function handleProduct(product: WarehouseProductSummary | null) {
    setSelectedProduct(product)
    onChange({ ...value, productId: product?.id })
  }

  function handleWorker(worker: WorkerResponse | null) {
    setSelectedWorker(worker)
    onChange({ ...value, receivedByWorkerId: worker?.id })
  }

  function handleFleetUnit(fleetUnit: FleetUnitResponse | null) {
    setSelectedFleetUnit(fleetUnit)
    // El trío disyunto: la unidad elegida va a su campo por `kind`, los otros dos
    // quedan sin filtro. Sin unidad, los tres se limpian.
    const { tractorId, trailerId, escortVehicleId } = fleetUnitToWithdrawalFields(fleetUnit)
    onChange({
      ...value,
      tractorId: tractorId ?? undefined,
      trailerId: trailerId ?? undefined,
      escortVehicleId: escortVehicleId ?? undefined,
    })
  }

  return (
    <Card padding="md" className="space-y-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <WarehouseProductField
          id="withdrawals-filter-product"
          label="Producto"
          selected={selectedProduct}
          onSelectedChange={handleProduct}
        />
        <WorkerField
          id="withdrawals-filter-worker"
          label="Recibido por"
          selected={selectedWorker}
          onSelectedChange={handleWorker}
        />
        <FleetUnitField
          id="withdrawals-filter-fleet-unit"
          label="Unidad de flota"
          selected={selectedFleetUnit}
          onSelectedChange={handleFleetUnit}
          placeholder="Tracto, carreta o escolta (opcional)…"
          loadErrorText={FLEET_UNITS_LOAD_ERROR}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div>
          <label
            htmlFor="withdrawals-status"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            Estado
          </label>
          <select
            id="withdrawals-status"
            value={value.status ?? ''}
            onChange={(event) =>
              onChange({
                ...value,
                status: event.target.value
                  ? (event.target.value as 'ACTIVE' | 'CANCELLED')
                  : undefined,
              })
            }
            className={inputClasses}
          >
            <option value="">Todos</option>
            <option value="ACTIVE">Activos</option>
            <option value="CANCELLED">Anulados</option>
          </select>
        </div>

        <div>
          <label
            htmlFor="withdrawals-date-from"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            Desde
          </label>
          <input
            id="withdrawals-date-from"
            type="date"
            value={value.dateFrom ?? ''}
            onChange={(event) => onChange({ ...value, dateFrom: event.target.value || undefined })}
            aria-invalid={invalidRange}
            aria-describedby={invalidRange ? 'withdrawals-date-error' : undefined}
            className={inputClasses}
          />
        </div>

        <div>
          <label
            htmlFor="withdrawals-date-to"
            className="mb-1.5 block text-sm font-medium text-fg-body"
          >
            Hasta
          </label>
          <input
            id="withdrawals-date-to"
            type="date"
            value={value.dateTo ?? ''}
            onChange={(event) => onChange({ ...value, dateTo: event.target.value || undefined })}
            aria-invalid={invalidRange}
            aria-describedby={invalidRange ? 'withdrawals-date-error' : undefined}
            className={inputClasses}
          />
        </div>
      </div>

      {invalidRange && (
        <p id="withdrawals-date-error" role="alert" className="text-xs text-danger">
          La fecha "desde" no puede ser posterior a la fecha "hasta".
        </p>
      )}
    </Card>
  )
}
