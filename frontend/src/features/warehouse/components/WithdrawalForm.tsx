import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type {
  FleetUnitResponse,
  WarehouseProductSummary,
  WarehouseWithdrawalResponse,
  WorkerResponse,
} from '../../../api'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { formatQuantity } from '../../../shared/utils/formatters'
import { useCreateWarehouseWithdrawal, toWithdrawalRequest } from '../hooks/useCreateWarehouseWithdrawal'
import { useWarehouseProductStock } from '../hooks/useWarehouseProductStock'
import {
  DEFAULT_WITHDRAWAL_VALUES,
  withdrawalFormSchema,
  type WithdrawalFormInput,
} from '../schemas/withdrawal.schema'
import { FleetUnitField } from './FleetUnitField'
import { WithdrawalProductField } from './WithdrawalProductField'
import { WorkerField } from './WorkerField'

interface WithdrawalFormProps {
  onCreated: (withdrawal: WarehouseWithdrawalResponse) => void
  onCancel: () => void
}

/** Campos del alta que aceptan un error de campo del backend. */
const FORM_FIELDS = ['productId', 'quantity', 'receivedByWorkerId', 'observations'] as const

/**
 * Registro de un retiro (salida de stock de un solo producto). A diferencia de la
 * entrada, no hay tabla de ítems ni proveedor ni moneda: un retiro descuenta la
 * cantidad de un producto y se recibe por un trabajador, con una unidad de flota
 * opcional.
 *
 * El disponible se muestra en vivo como orientación, pero no bloquea el envío: el
 * stock puede cambiar entre la lectura y el POST, así que la autoridad es el 409
 * WH-001 del backend (validado bajo lock), que se ancla al campo cantidad.
 */
export function WithdrawalForm({ onCreated, onCancel }: WithdrawalFormProps) {
  const createWithdrawal = useCreateWarehouseWithdrawal()
  const [selectedProduct, setSelectedProduct] = useState<WarehouseProductSummary | null>(null)
  const [selectedWorker, setSelectedWorker] = useState<WorkerResponse | null>(null)
  const [selectedFleetUnit, setSelectedFleetUnit] = useState<FleetUnitResponse | null>(null)

  const {
    control,
    register,
    handleSubmit,
    setValue,
    setError,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<WithdrawalFormInput>({
    resolver: zodResolver(withdrawalFormSchema),
    mode: 'onTouched',
    defaultValues: DEFAULT_WITHDRAWAL_VALUES,
  })

  const stockQuery = useWarehouseProductStock(selectedProduct?.id ?? null)
  const quantity = useWatch({ control, name: 'quantity' })

  function applyProduct(product: WarehouseProductSummary | null) {
    setSelectedProduct(product)
    setValue('productId', product?.id ?? 0, { shouldValidate: true, shouldTouch: true })
  }

  function applyWorker(worker: WorkerResponse | null) {
    setSelectedWorker(worker)
    setValue('receivedByWorkerId', worker?.id ?? 0, { shouldValidate: true, shouldTouch: true })
  }

  const available = stockQuery.data?.stock
  const exceedsStock =
    available != null && Number.isFinite(quantity) && quantity > available

  const isPending = isSubmitting || createWithdrawal.isPending

  const onSubmit = handleSubmit((values) => {
    createWithdrawal.mutate(toWithdrawalRequest(values, selectedFleetUnit), {
      onSuccess: onCreated,
      onError: (error) => {
        handleApiFormError(error, {
          setError,
          fallbackMessage: 'No se pudo registrar el retiro. Intenta de nuevo.',
          // El stock insuficiente se corrige en la cantidad: es el campo accionable,
          // y el detalle del backend trae el disponible.
          codeFieldMap: { 'WH-001': 'quantity' },
          allowedFields: FORM_FIELDS,
        })
      },
    })
  })

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Salida</h2>

        {/* Producto ocupa la mayor parte del ancho (necesita sitio para buscar); la
            cantidad es un campo corto que va a su lado. */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3 sm:items-start">
          <div className="sm:col-span-2">
            <WithdrawalProductField
              id="withdrawal-product"
              label="Producto"
              selected={selectedProduct}
              onSelectedChange={applyProduct}
              onBlur={() => trigger('productId')}
              error={errors.productId?.message}
            />
          </div>
          <TextField
            id="withdrawal-quantity"
            label="Cantidad"
            type="number"
            step="any"
            min={0}
            error={errors.quantity?.message}
            register={register('quantity', { valueAsNumber: true })}
          />
        </div>

        {selectedProduct && (
          <p className="text-xs text-slate-500" aria-live="polite">
            {stockQuery.isLoading
              ? 'Cargando stock disponible…'
              : stockQuery.isError
                ? 'No se pudo leer el stock disponible.'
                : available != null &&
                  `Disponible: ${formatQuantity(available)} ${selectedProduct.unitCode}.`}
          </p>
        )}

        {exceedsStock && (
          <p role="alert" className="text-xs text-amber-700">
            La cantidad supera el stock disponible ({formatQuantity(available)}{' '}
            {selectedProduct?.unitCode}). Puedes registrarlo igual: si al confirmar no alcanza, el
            sistema lo rechaza.
          </p>
        )}
      </section>

      <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Destino</h2>

        {/* Receptor y unidad al mismo nivel: son los dos datos del destino del retiro. */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 sm:items-start">
          <WorkerField
            id="withdrawal-worker"
            label="Recibido por"
            selected={selectedWorker}
            onSelectedChange={applyWorker}
            onBlur={() => trigger('receivedByWorkerId')}
            error={errors.receivedByWorkerId?.message}
          />

          <FleetUnitField
            id="withdrawal-fleet-unit"
            label="Unidad de flota (opcional)"
            selected={selectedFleetUnit}
            onSelectedChange={setSelectedFleetUnit}
          />
        </div>
      </section>

      <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <Textarea
          id="withdrawal-observations"
          label="Observaciones (opcional)"
          rows={3}
          error={errors.observations?.message}
          register={register('observations')}
        />
      </div>

      <div className="flex justify-end gap-3">
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        >
          Cancelar
        </button>
        <button
          type="submit"
          disabled={isPending}
          className="rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isPending ? 'Registrando…' : 'Registrar retiro'}
        </button>
      </div>
    </form>
  )
}
