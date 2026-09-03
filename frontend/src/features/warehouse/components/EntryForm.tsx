import { useEffect, useRef, useState } from 'react'
import { FormProvider, useForm, useWatch, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { WarehousePurchaseInvoiceResponse, WarehouseSupplierResponse } from '../../../api'
import { DateField } from '../../../shared/ui/DateField'
import { SelectField } from '../../../shared/ui/SelectField'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import {
  getApiErrorMessage,
  isPreconditionFailedError,
} from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { todayIsoDate } from '../../../shared/utils/formatters'
import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import {
  toPurchaseInvoiceRequest,
  useCreateWarehousePurchaseInvoice,
} from '../hooks/useCreateWarehousePurchaseInvoice'
import {
  toPurchaseInvoiceUpdateRequest,
  useUpdateWarehousePurchaseInvoice,
} from '../hooks/useUpdateWarehousePurchaseInvoice'
import type { WarehousePurchaseInvoiceWithEtag } from '../hooks/useWarehousePurchaseInvoice'
import {
  EMPTY_INVOICE_ITEM,
  purchaseInvoiceEditFormSchema,
  purchaseInvoiceFormSchema,
  type PurchaseInvoiceEditFormInput,
} from '../schemas/purchase-invoice.schema'
import { EntryItemsTable } from './EntryItemsTable'
import { SupplierField } from './SupplierField'
import { Button } from '../../../shared/ui/Button'
import { Card } from '../../../shared/ui/Card'
import { Alert } from '../../../shared/ui/Alert'

interface EntryCreateModeProps {
  mode: 'create'
  onCreated: (invoice: WarehousePurchaseInvoiceResponse) => void
  onCancel: () => void
}

interface EntryEditModeProps {
  mode: 'edit'
  invoice: WarehousePurchaseInvoiceWithEtag
  onUpdated: (invoice: WarehousePurchaseInvoiceResponse) => void
  onCancel: () => void
  /** El detalle recarga para tomar la versión de quien haya editado antes (412). */
  onReloadRequested: () => void
}

type EntryFormProps = EntryCreateModeProps | EntryEditModeProps

/** Moneda de la mayoría de las compras: se preselecciona por código, nunca por id. */
const DEFAULT_CURRENCY_CODE = 'PEN'

/** Campos del alta que aceptan un error de campo del backend. */
const FORM_FIELDS = [
  'supplierId',
  'invoiceNumber',
  'invoiceDate',
  'guideNumber',
  'currencyId',
  'observations',
] as const

/** En edición el proveedor es read-only (no se envía); suma `reason`. */
const FORM_FIELDS_EDIT = [
  'invoiceNumber',
  'invoiceDate',
  'guideNumber',
  'currencyId',
  'observations',
  'reason',
] as const

const DEFAULT_VALUES: PurchaseInvoiceEditFormInput = {
  supplierId: 0,
  invoiceNumber: '',
  invoiceDate: '',
  guideNumber: '',
  currencyId: 0,
  observations: '',
  reason: '',
  items: [EMPTY_INVOICE_ITEM],
}

/** Prefill del form de edición desde el detalle. La fecha ya llega `YYYY-MM-DD`. */
function toEditDefaults(invoice: WarehousePurchaseInvoiceWithEtag): PurchaseInvoiceEditFormInput {
  return {
    // El proveedor viaja en el form (read-only) pero el mapper del PUT lo descarta.
    supplierId: invoice.supplier.id,
    invoiceNumber: invoice.invoiceNumber,
    invoiceDate: invoice.invoiceDate,
    guideNumber: invoice.guideNumber ?? '',
    currencyId: invoice.currency.id,
    observations: invoice.observations ?? '',
    reason: '',
    items: invoice.items.map((item) => ({
      productId: item.product.id,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
    })),
  }
}

/**
 * Alta y edición de una entrada (factura de compra), en un solo componente de
 * página: los campos, la tabla de ítems y las validaciones son los mismos, y
 * separarlos en dos forms los desincronizaría con el primer cambio.
 *
 * Lo que cambia por modo: el proveedor (combobox al crear, read-only al editar
 * porque el contrato no lo acepta en el PUT), el motivo obligatorio de la edición,
 * la preselección de moneda (solo al crear) y el optimistic locking con `If-Match`.
 *
 * El total no es un campo del form ni viaja al backend: se deriva de los ítems.
 * Las monedas se esperan antes de montar: `currencyId` es obligatorio y un select
 * vacío llevaría derecho a un 400.
 */
export function EntryForm(props: EntryFormProps) {
  const isCreate = props.mode === 'create'
  const currencies = useCurrencies()
  const createInvoice = useCreateWarehousePurchaseInvoice()
  const updateInvoice = useUpdateWarehousePurchaseInvoice()
  const [selectedSupplier, setSelectedSupplier] = useState<WarehouseSupplierResponse | null>(null)

  const form = useForm<PurchaseInvoiceEditFormInput>({
    // Cada modo valida con su schema; el de edición suma el motivo. El cast salva
    // que el schema de alta no tipa `reason` (queda en el form, no se valida).
    resolver: (isCreate
      ? zodResolver(purchaseInvoiceFormSchema)
      : zodResolver(purchaseInvoiceEditFormSchema)) as Resolver<PurchaseInvoiceEditFormInput>,
    mode: 'onTouched',
    defaultValues: props.mode === 'edit' ? toEditDefaults(props.invoice) : DEFAULT_VALUES,
  })
  const {
    control,
    register,
    handleSubmit,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = form

  const currencyId = useWatch({ control, name: 'currencyId' })
  const selectedCurrency = currencies.data?.find((currency) => currency.id === currencyId)

  // La preselección va en un efecto porque el catálogo llega después del montaje,
  // y react-hook-form congela los defaults al montar. Corre UNA sola vez y SOLO al
  // crear: en edición la moneda viene del prefill.
  const currencyPreselected = useRef(false)
  useEffect(() => {
    if (!isCreate || currencyPreselected.current || !currencies.data) return
    currencyPreselected.current = true
    const preferred = currencies.data.find((currency) => currency.code === DEFAULT_CURRENCY_CODE)
    if (preferred) setValue('currencyId', preferred.id)
  }, [isCreate, currencies.data, setValue])

  // Sin el ETag del header no se puede armar el If-Match, y el PUT lo exige. Pasa si
  // el gateway no expone el header (falta `cors.exposed-headers=ETag`).
  const missingEtag = props.mode === 'edit' && !props.invoice._etag
  const versionConflict = props.mode === 'edit' && isPreconditionFailedError(updateInvoice.error)

  const onSubmit = handleSubmit((values) => {
    if (props.mode === 'create') {
      createInvoice.mutate(toPurchaseInvoiceRequest(values), {
        onSuccess: props.onCreated,
        onError: (error) => {
          handleApiFormError(error, {
            setError,
            fallbackMessage: 'No se pudo registrar la entrada. Intenta de nuevo.',
            // El duplicado (mismo proveedor + mismo número) se corrige en el número
            // de factura: es el campo que el usuario puede arreglar.
            codeFieldMap: { 'WH-002': 'invoiceNumber' },
            allowedFields: FORM_FIELDS,
          })
        },
      })
      return
    }

    const { invoice } = props
    if (!invoice._etag) return
    updateInvoice.mutate(
      { id: invoice.id, ifMatch: invoice._etag, body: toPurchaseInvoiceUpdateRequest(values) },
      {
        onSuccess: props.onUpdated,
        onError: (error) => {
          // El conflicto de versión no es un error de campo: se muestra en el aviso
          // de arriba, con la salida de recargar.
          if (isPreconditionFailedError(error)) return
          handleApiFormError(error, {
            setError,
            fallbackMessage: 'No se pudo guardar la entrada. Intenta de nuevo.',
            codeFieldMap: { 'WH-002': 'invoiceNumber' },
            allowedFields: FORM_FIELDS_EDIT,
          })
        },
      },
    )
  })

  if (currencies.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando catálogos" className="text-blue-600" />
      </div>
    )
  }

  if (currencies.isError) {
    return (
      <div role="alert" className="flex flex-col items-center px-6 py-16 text-center">
        <p className="text-sm font-medium text-slate-700">
          {getApiErrorMessage(currencies.error, 'No se pudieron cargar las monedas.')}
        </p>
        <Button variant="secondary" onClick={() => currencies.refetch()} className="mt-4">
          Reintentar
        </Button>
      </div>
    )
  }

  const isPending = isSubmitting || createInvoice.isPending || updateInvoice.isPending

  return (
    <FormProvider {...form}>
      <form onSubmit={onSubmit} noValidate className="space-y-6">
        {props.mode === 'edit' && versionConflict && (
          <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-amber-800">
            <span>
              {getApiErrorMessage(
                updateInvoice.error,
                'Otro usuario cambió esta factura mientras la editabas.',
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
            No se puede guardar: falta la versión de la entrada. Recarga la página e intenta de nuevo.
          </Alert>
        )}

        {props.mode === 'edit' && (
          <Card>
            <Textarea
              id="entry-edit-reason"
              label="Motivo de edición"
              rows={3}
              helperText="Queda registrado en la auditoría. Mínimo 10 caracteres."
              error={errors.reason?.message}
              register={register('reason')}
            />
          </Card>
        )}

        <Card as="section" className="space-y-4">
          <h2 className="text-sm font-semibold text-slate-900">Factura</h2>

          {props.mode === 'edit' ? (
            <div>
              <p className="mb-1.5 block text-sm font-medium text-slate-700">Proveedor</p>
              <p className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-500">
                {props.invoice.supplier.name}
                {props.invoice.supplier.ruc ? ` · RUC ${props.invoice.supplier.ruc}` : ''}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                El proveedor no se puede cambiar al editar la factura.
              </p>
            </div>
          ) : (
            <SupplierField selected={selectedSupplier} onSelectedChange={setSelectedSupplier} />
          )}

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <TextField
              id="entry-invoice-number"
              label="N° de factura"
              error={errors.invoiceNumber?.message}
              register={register('invoiceNumber')}
            />
            <DateField
              id="entry-invoice-date"
              label="Fecha de factura"
              name="invoiceDate"
              control={control}
              // Una factura de compra no se emite a futuro. El backend todavía no
              // lo restringe, así que la regla vive acá y en el schema.
              max={todayIsoDate()}
              error={errors.invoiceDate?.message}
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <TextField
              id="entry-guide-number"
              label="N° de guía de remisión (opcional)"
              error={errors.guideNumber?.message}
              register={register('guideNumber')}
            />
            <SelectField
              id="entry-currency"
              label="Moneda"
              name="currencyId"
              control={control}
              // Mismo formato que el wizard de cotizaciones: es el mismo catálogo
              // y verlo distinto según la pantalla confunde.
              options={(currencies.data ?? []).map((currency) => ({
                value: currency.id,
                label: `${currency.code} (${currency.symbol})`,
              }))}
              placeholder="Seleccionar…"
              error={errors.currencyId?.message}
            />
          </div>
        </Card>

        <Card>
          <EntryItemsTable
            currencyCode={selectedCurrency?.code}
            initialSelectedProducts={
              props.mode === 'edit' ? props.invoice.items.map((item) => item.product) : undefined
            }
          />
        </Card>

        <Card>
          <Textarea
            id="entry-observations"
            label="Observaciones"
            rows={3}
            error={errors.observations?.message}
            register={register('observations')}
          />
        </Card>

        {/* Los dos botones del pie NO usan `Button`, y no es un olvido: son otra forma.
            Llevan `px-4 py-2.5` en vez de `px-4 py-2`, `focus-visible:` en vez de `focus:`
            y no son `inline-flex`. Unificarlos cambiaría el aspecto, que es justo lo que la
            mudanza del botón compartido no hace; entran cuando se decida la forma buena,
            mismo criterio que el botón de contorno rojo de `WizardForm`. */}
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
            {isCreate
              ? isPending
                ? 'Registrando…'
                : 'Registrar entrada'
              : isPending
                ? 'Guardando…'
                : 'Guardar cambios'}
          </button>
        </div>
      </form>
    </FormProvider>
  )
}
