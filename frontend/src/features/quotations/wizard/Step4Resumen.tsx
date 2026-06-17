import { useFormContext } from 'react-hook-form'
import { QuotationItemsSection } from '../components/QuotationItemsSection'
import { QuotationStandbyTable } from '../components/QuotationStandbyTable'
import { QuotationTotalGeneral } from '../components/QuotationTotalGeneral'
import { Step4SummaryCard } from './Step4SummaryCard'
import { QuotationNotesFields } from './QuotationNotesFields'
import { quotationFormItemsToResponse, quotationTotals } from './quotationFormToResponse'
import { montoEnLetras } from '../utils/montoEnLetras'
import type { WizardFormInput } from './quotation-wizard.schema'
import type {
  ClientResponse,
  CurrencyResponse,
  PaymentTermResponse,
  QuotationServiceTypeResponse,
} from '../../../api'

interface Step4ResumenProps {
  selectedClient: ClientResponse | null
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
  serviceTypes: QuotationServiceTypeResponse[]
  igvPercentage: number
}

/**
 * Step 4 (read-only): Resumen final antes de guardar. Lee del form y reusa los componentes
 * del Detalle de cotización (tabla jerárquica, stand-by, total) mapeando el form al shape de
 * la API. El botón "Guardar" + el submit (`POST /quotations`) se implementan en un PR posterior.
 */
export function Step4Resumen({
  selectedClient,
  currencies,
  paymentTerms,
  serviceTypes,
  igvPercentage,
}: Step4ResumenProps) {
  const { watch } = useFormContext<WizardFormInput>()
  const items = watch('items') ?? []
  const currencyId = watch('currencyId')
  const currencyCode = currencies.find((currency) => currency.id === currencyId)?.code ?? 'PEN'

  const mappedItems = quotationFormItemsToResponse(items, serviceTypes, igvPercentage)
  const { subtotal, igv, total } = quotationTotals(mappedItems, igvPercentage)

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Resumen final</h2>
        <p className="text-xs text-slate-500">Revisa los datos antes de guardar la cotización.</p>
      </div>

      <Step4SummaryCard selectedClient={selectedClient} currencies={currencies} paymentTerms={paymentTerms} />

      {mappedItems.length === 0 ? (
        <div
          role="alert"
          className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-6 py-10 text-center text-sm text-slate-500"
        >
          Agrega ítems en el paso 2 para ver el resumen.
        </div>
      ) : (
        <>
          <QuotationItemsSection items={mappedItems} currencyCode={currencyCode} subtotal={subtotal} igv={igv} />
          <QuotationStandbyTable items={mappedItems} currencyCode={currencyCode} />
          <QuotationTotalGeneral
            total={total}
            currencyCode={currencyCode}
            amountInWords={montoEnLetras(total, currencyCode)}
          />
        </>
      )}

      <QuotationNotesFields />
    </div>
  )
}
