import type { ReactNode } from 'react'
import { Building2, FileText, MapPin, type LucideIcon } from 'lucide-react'
import { useFormContext } from 'react-hook-form'
import { formatDateOnly } from '../../../shared/utils/formatters'
import type { WizardFormInput } from './quotation-wizard.schema'
import type { ClientResponse, CurrencyResponse, PaymentTermResponse } from '../../../api'

interface Step4SummaryCardProps {
  selectedClient: ClientResponse | null
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
}

function InfoCard({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-500">
        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        {title}
      </h2>
      <div className="mt-3">{children}</div>
    </section>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3 py-0.5 text-sm">
      <span className="text-slate-500">{label}</span>
      <span className="text-right font-medium text-slate-900">{value}</span>
    </div>
  )
}

/**
 * Tres tarjetas de cabecera del Resumen (Cliente · Ruta · Condiciones), leídas del FORM.
 * Paralelo a `QuotationSummaryCard` (que lee de la API): el wizard no tiene un
 * `QuotationResponse` todavía, así que arma el resumen desde el form + el cliente elegido.
 */
export function Step4SummaryCard({ selectedClient, currencies, paymentTerms }: Step4SummaryCardProps) {
  const { watch } = useFormContext<WizardFormInput>()
  const quotationType = watch('quotationType')
  const contactName = watch('contactName')
  const contactPhone = watch('contactPhone')
  const currencyId = watch('currencyId')
  const paymentTermId = watch('paymentTermId')
  const validityDays = watch('validityDays')
  const origin = watch('origin')
  const destination = watch('destination')
  const tentativeServiceDate = watch('tentativeServiceDate')

  const currency = currencies.find((item) => item.id === currencyId)
  const paymentTerm = paymentTerms.find((item) => item.id === paymentTermId)
  const hasRoute = quotationType === 'TRANSPORTE' && !!origin && !!destination

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
      <InfoCard icon={Building2} title="Cliente">
        {selectedClient ? (
          <>
            <p className="text-sm font-semibold text-slate-900">{selectedClient.name}</p>
            <p className="text-xs text-slate-500">RUC {selectedClient.ruc}</p>
            {(contactName || contactPhone) && (
              <div className="mt-2">
                {contactName && <p className="text-sm text-slate-700">{contactName}</p>}
                {contactPhone && <p className="text-xs text-slate-500">{contactPhone}</p>}
              </div>
            )}
          </>
        ) : (
          <p className="text-sm text-slate-500">Sin cliente seleccionado</p>
        )}
      </InfoCard>

      <InfoCard icon={MapPin} title="Ruta">
        {hasRoute ? (
          <div className="text-sm text-slate-800">
            <p className="font-medium">{origin}</p>
            <p className="my-0.5 text-blue-600" aria-hidden="true">
              ↓
            </p>
            <p className="font-medium">{destination}</p>
          </div>
        ) : (
          <p className="text-sm text-slate-500">—</p>
        )}
      </InfoCard>

      <InfoCard icon={FileText} title="Condiciones comerciales">
        <Row label="Moneda" value={currency?.code ?? '—'} />
        <Row label="Pago" value={paymentTerm?.name ?? '—'} />
        <Row
          label="Fecha tentativa"
          value={tentativeServiceDate ? formatDateOnly(tentativeServiceDate) : '—'}
        />
        <Row label="Validez" value={validityDays ? `${validityDays} días` : '—'} />
      </InfoCard>
    </div>
  )
}
