import { useState } from 'react'
import { useForm, useWatch, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type {
  FleetUnitRef,
  WarehouseProductSummary,
  WarehouseWithdrawalResponse,
  WorkerResponse,
} from '../../../api'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import {
  getApiErrorMessage,
  isPreconditionFailedError,
} from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { formatQuantity } from '../../../shared/utils/formatters'
import { useCreateWarehouseWithdrawal, toWithdrawalRequest } from '../hooks/useCreateWarehouseWithdrawal'
import {
  toWithdrawalUpdateRequest,
  useUpdateWarehouseWithdrawal,
} from '../hooks/useUpdateWarehouseWithdrawal'
import type { WarehouseWithdrawalWithEtag } from '../hooks/useWarehouseWithdrawal'
import { useWarehouseProductStock } from '../hooks/useWarehouseProductStock'
import {
  DEFAULT_WITHDRAWAL_VALUES,
  withdrawalEditFormSchema,
  withdrawalFormSchema,
  type WithdrawalEditFormInput,
} from '../schemas/withdrawal.schema'
import { FleetUnitField } from '../../../shared/catalogs/FleetUnitField'
import { WarehouseProductField } from './WarehouseProductField'
import { WorkerField } from './WorkerField'
import { Card } from '../../../shared/ui/Card'
import { Alert } from '../../../shared/ui/Alert'

interface WithdrawalCreateModeProps {
  mode: 'create'
  onCreated: (withdrawal: WarehouseWithdrawalResponse) => void
  onCancel: () => void
}

interface WithdrawalEditModeProps {
  mode: 'edit'
  withdrawal: WarehouseWithdrawalWithEtag
  onUpdated: (withdrawal: WarehouseWithdrawalResponse) => void
  onCancel: () => void
  /** El detalle recarga para tomar la versión de quien haya editado antes (412). */
  onReloadRequested: () => void
}

type WithdrawalFormProps = WithdrawalCreateModeProps | WithdrawalEditModeProps

/** Campos del alta que aceptan un error de campo del backend. */
const FORM_FIELDS = ['productId', 'quantity', 'receivedByWorkerId', 'observations'] as const

/** En edición el producto es read-only (no se envía); suma `reason`. */
const FORM_FIELDS_EDIT = ['quantity', 'receivedByWorkerId', 'observations', 'reason'] as const

/** Prefill del form de edición desde el detalle del retiro. */
function toEditDefaults(withdrawal: WarehouseWithdrawalWithEtag): WithdrawalEditFormInput {
  return {
    // El producto viaja en el form (read-only) pero el mapper del PUT lo descarta.
    productId: withdrawal.product.id,
    quantity: withdrawal.quantity,
    receivedByWorkerId: withdrawal.receivedBy.id,
    observations: withdrawal.observations ?? '',
    reason: '',
  }
}

/**
 * Alta y edición de un retiro (salida de stock de un solo producto). A diferencia
 * de la entrada, no hay tabla de ítems ni proveedor ni moneda: un retiro descuenta
 * la cantidad de un producto y se recibe por un trabajador, con una unidad de flota
 * opcional.
 *
 * Lo que cambia por modo: el producto (combobox al crear, read-only al editar
 * porque el contrato no lo acepta en el PUT), el motivo obligatorio de la edición y
 * el optimistic locking con `If-Match`.
 *
 * El disponible se muestra en vivo como orientación, pero no bloquea el envío: el
 * stock puede cambiar entre la lectura y el envío, así que la autoridad es el 409
 * WH-001 del backend (validado bajo lock), que se ancla al campo cantidad.
 */
export function WithdrawalForm(props: WithdrawalFormProps) {
  const isEdit = props.mode === 'edit'
  const createWithdrawal = useCreateWarehouseWithdrawal()
  const updateWithdrawal = useUpdateWarehouseWithdrawal()

  const [selectedProduct, setSelectedProduct] = useState<WarehouseProductSummary | null>(
    props.mode === 'edit' ? props.withdrawal.product : null,
  )
  const [selectedWorker, setSelectedWorker] = useState<WorkerResponse | null>(
    props.mode === 'edit' ? props.withdrawal.receivedBy : null,
  )
  const [selectedFleetUnit, setSelectedFleetUnit] = useState<FleetUnitRef | null>(
    props.mode === 'edit' ? (props.withdrawal.fleetUnit ?? null) : null,
  )

  const {
    control,
    register,
    handleSubmit,
    setValue,
    setError,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<WithdrawalEditFormInput>({
    // Cada modo valida con su schema; el de edición suma el motivo. El cast salva
    // que el schema de alta no tipa `reason` (queda en el form, no se valida).
    resolver: (isEdit
      ? zodResolver(withdrawalEditFormSchema)
      : zodResolver(withdrawalFormSchema)) as Resolver<WithdrawalEditFormInput>,
    mode: 'onTouched',
    defaultValues:
      props.mode === 'edit'
        ? toEditDefaults(props.withdrawal)
        : { ...DEFAULT_WITHDRAWAL_VALUES, reason: '' },
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

  // El endpoint de stock devuelve el disponible con ESTE retiro ya descontado,
  // mientras el backend valida la edición contra el disponible SIN contarlo. Al
  // editar hay que devolverle esa cantidad, o bajar de 10 a 8 avisaría "supera el
  // disponible" siendo falso. Es aritmética segura: a la edición no se llega si el
  // retiro está anulado, así que su cantidad sigue descontada del stock.
  const available =
    stockQuery.data == null
      ? undefined
      : props.mode === 'edit'
        ? stockQuery.data.stock + props.withdrawal.quantity
        : stockQuery.data.stock
  const exceedsStock = available != null && Number.isFinite(quantity) && quantity > available

  // Sin el ETag del header no se puede armar el If-Match, y el PUT lo exige. Pasa si
  // el gateway no expone el header (falta `cors.exposed-headers=ETag`).
  const missingEtag = props.mode === 'edit' && !props.withdrawal._etag
  const versionConflict = props.mode === 'edit' && isPreconditionFailedError(updateWithdrawal.error)

  const isPending = isSubmitting || createWithdrawal.isPending || updateWithdrawal.isPending

  const onSubmit = handleSubmit((values) => {
    if (props.mode !== 'edit') {
      createWithdrawal.mutate(toWithdrawalRequest(values, selectedFleetUnit), {
        onSuccess: props.onCreated,
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
      return
    }

    const { withdrawal } = props
    if (!withdrawal._etag) return
    updateWithdrawal.mutate(
      {
        id: withdrawal.id,
        ifMatch: withdrawal._etag,
        body: toWithdrawalUpdateRequest(values, selectedFleetUnit),
      },
      {
        onSuccess: props.onUpdated,
        onError: (error) => {
          // El conflicto de versión no es un error de campo: se muestra en el aviso
          // de arriba, con la salida de recargar.
          if (isPreconditionFailedError(error)) return
          handleApiFormError(error, {
            setError,
            fallbackMessage: 'No se pudo guardar el retiro. Intenta de nuevo.',
            codeFieldMap: { 'WH-001': 'quantity' },
            allowedFields: FORM_FIELDS_EDIT,
          })
        },
      },
    )
  })

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      {props.mode === 'edit' && versionConflict && (
        <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-amber-800">
          <span>
            {getApiErrorMessage(
              updateWithdrawal.error,
              'Otro usuario cambió este retiro mientras lo editabas.',
            )}{' '}
            Al recargar se pierde lo que escribiste.
          </span>
          <button
            type="button"
            onClick={props.onReloadRequested}
            className="shrink-0 font-medium text-amber-900 underline underline-offset-2 hover:no-underline"
          >
            Descartar y recargar
          </button>
        </Alert>
      )}

      {missingEtag && (
        <Alert as="p" bordered={false} role="alert" className="rounded-lg px-4 py-2.5 text-sm text-red-700">
          No se puede guardar: falta la versión del retiro. Recarga la página e intenta de nuevo.
        </Alert>
      )}

      {props.mode === 'edit' && (
        <Card>
          <Textarea
            id="withdrawal-edit-reason"
            label="Motivo de edición"
            rows={3}
            helperText="Queda registrado en la auditoría. Mínimo 10 caracteres."
            error={errors.reason?.message}
            register={register('reason')}
          />
        </Card>
      )}

      <Card as="section" className="space-y-4">
        <h2 className="text-sm font-semibold text-slate-900">Salida</h2>

        {/* Producto ocupa la mayor parte del ancho (necesita sitio para buscar); la
            cantidad es un campo corto que va a su lado. */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3 sm:items-start">
          <div className="sm:col-span-2">
            {props.mode === 'edit' ? (
              <div>
                <p className="mb-1.5 block text-sm font-medium text-slate-700">Producto</p>
                <p className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-500">
                  {props.withdrawal.product.name}
                  {props.withdrawal.product.code
                    ? ` · ${props.withdrawal.product.code} · ${props.withdrawal.product.unitCode}`
                    : ''}
                </p>
                <p className="mt-1 text-xs text-slate-500">
                  El producto no se puede cambiar: si es el equivocado, anula el retiro y registra
                  otro.
                </p>
              </div>
            ) : (
              <WarehouseProductField
                id="withdrawal-product"
                label="Producto"
                selected={selectedProduct}
                onSelectedChange={applyProduct}
                onBlur={() => trigger('productId')}
                error={errors.productId?.message}
              />
            )}
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
                  (isEdit
                    ? `Disponible para este retiro: ${formatQuantity(available)} ${selectedProduct.unitCode} (incluye lo ya retirado acá).`
                    : `Disponible: ${formatQuantity(available)} ${selectedProduct.unitCode}.`)}
          </p>
        )}

        {exceedsStock && (
          <p role="alert" className="text-xs text-amber-700">
            La cantidad supera el stock disponible ({formatQuantity(available)}{' '}
            {selectedProduct?.unitCode}). Puedes guardarlo igual: si al confirmar no alcanza, el
            sistema lo rechaza.
          </p>
        )}
      </Card>

      <Card as="section" className="space-y-4">
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
            placeholder="Tracto, carreta o escolta (opcional)…"
            loadErrorText="No se pudieron cargar las unidades de flota. El retiro se puede registrar sin unidad."
          />
        </div>
      </Card>

      <Card>
        <Textarea
          id="withdrawal-observations"
          label="Observaciones (opcional)"
          rows={3}
          error={errors.observations?.message}
          register={register('observations')}
        />
      </Card>

      <div className="flex justify-end gap-3">
        <button
          type="button"
          onClick={props.onCancel}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        >
          Cancelar
        </button>
        <button
          type="submit"
          disabled={isPending || missingEtag}
          className="rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isEdit
            ? isPending
              ? 'Guardando…'
              : 'Guardar cambios'
            : isPending
              ? 'Registrando…'
              : 'Registrar retiro'}
        </button>
      </div>
    </form>
  )
}
