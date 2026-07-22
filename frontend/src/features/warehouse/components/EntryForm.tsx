import { useEffect, useRef, useState } from 'react'
import { FormProvider, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { WarehousePurchaseInvoiceResponse, WarehouseSupplierResponse } from '../../../api'
import { DateField } from '../../../shared/ui/DateField'
import { SelectField } from '../../../shared/ui/SelectField'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { todayIsoDate } from '../../../shared/utils/formatters'
import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import {
  toPurchaseInvoiceRequest,
  useCreateWarehousePurchaseInvoice,
} from '../hooks/useCreateWarehousePurchaseInvoice'
import {
  EMPTY_INVOICE_ITEM,
  purchaseInvoiceFormSchema,
  type PurchaseInvoiceFormInput,
} from '../schemas/purchase-invoice.schema'
import { EntryItemsTable } from './EntryItemsTable'
import { SupplierField } from './SupplierField'

interface EntryFormProps {
  onCreated: (invoice: WarehousePurchaseInvoiceResponse) => void
  onCancel: () => void
}

/** Moneda de la mayoría de las compras: se preselecciona por código, nunca por id. */
const DEFAULT_CURRENCY_CODE = 'PEN'

const FORM_FIELDS = [
  'supplierId',
  'invoiceNumber',
  'invoiceDate',
  'guideNumber',
  'currencyId',
  'observations',
] as const

const DEFAULT_VALUES: PurchaseInvoiceFormInput = {
  supplierId: 0,
  invoiceNumber: '',
  invoiceDate: '',
  guideNumber: '',
  currencyId: 0,
  observations: '',
  items: [EMPTY_INVOICE_ITEM],
}

/**
 * Registro de una entrada (factura de compra). El total no es un campo del form
 * ni viaja al backend: se deriva de los ítems, porque el contrato define los
 * totales como derivados y nunca persistidos.
 *
 * Las monedas se esperan antes de montar el form: `currencyId` es obligatorio y
 * un select vacío llevaría derecho a un 400.
 */
export function EntryForm({ onCreated, onCancel }: EntryFormProps) {
  const currencies = useCurrencies()
  const createInvoice = useCreateWarehousePurchaseInvoice()
  const [selectedSupplier, setSelectedSupplier] = useState<WarehouseSupplierResponse | null>(null)

  const form = useForm<PurchaseInvoiceFormInput>({
    resolver: zodResolver(purchaseInvoiceFormSchema),
    mode: 'onTouched',
    defaultValues: DEFAULT_VALUES,
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
  // y react-hook-form congela los defaults al montar. Corre UNA sola vez: mirar el
  // valor actual del campo haría que volver a la opción vacía reponga la moneda.
  const currencyPreselected = useRef(false)
  useEffect(() => {
    if (currencyPreselected.current || !currencies.data) return
    currencyPreselected.current = true
    const preferred = currencies.data.find(
      (currency) => currency.code === DEFAULT_CURRENCY_CODE,
    )
    if (preferred) setValue('currencyId', preferred.id)
  }, [currencies.data, setValue])

  const onSubmit = handleSubmit((values) => {
    createInvoice.mutate(toPurchaseInvoiceRequest(values), {
      onSuccess: onCreated,
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
        <button
          type="button"
          onClick={() => currencies.refetch()}
          className="mt-4 inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          Reintentar
        </button>
      </div>
    )
  }

  const isPending = isSubmitting || createInvoice.isPending

  return (
    <FormProvider {...form}>
      <form onSubmit={onSubmit} noValidate className="space-y-6">
        <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Factura</h2>

          <SupplierField selected={selectedSupplier} onSelectedChange={setSelectedSupplier} />

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
        </section>

        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <EntryItemsTable currencyCode={selectedCurrency?.code} />
        </div>

        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <Textarea
            id="entry-observations"
            label="Observaciones"
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
            {isPending ? 'Registrando…' : 'Registrar entrada'}
          </button>
        </div>
      </form>
    </FormProvider>
  )
}
