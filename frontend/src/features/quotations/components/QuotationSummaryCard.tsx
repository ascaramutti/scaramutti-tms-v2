import type { ReactNode } from 'react'
import { Building2, FileText, MapPin, type LucideIcon } from 'lucide-react'
import { formatDateOnly } from '../../../shared/utils/formatters'
import type { QuotationResponse } from '../../../api'

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

/** Tres tarjetas de cabecera: Cliente · Ruta · Condiciones comerciales. */
export function QuotationSummaryCard({ quotation }: { quotation: QuotationResponse }) {
  const {
    client,
    contactName,
    contactPhone,
    currency,
    paymentTerm,
    validityDays,
    origin,
    destination,
    quotationType,
    tentativeServiceDate,
  } = quotation
  const hasRoute = quotationType === 'TRANSPORTE' && !!origin && !!destination

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
      <InfoCard icon={Building2} title="Cliente">
        <p className="text-sm font-semibold text-slate-900">{client.name}</p>
        <p className="text-xs text-slate-500">RUC {client.ruc}</p>
        {(contactName || contactPhone) && (
          <div className="mt-2">
            {contactName && <p className="text-sm text-slate-700">{contactName}</p>}
            {contactPhone && <p className="text-xs text-slate-500">{contactPhone}</p>}
          </div>
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
        <Row label="Moneda" value={currency.code} />
        <Row label="Pago" value={paymentTerm?.name ?? '—'} />
        <Row
          label="Fecha tentativa"
          value={tentativeServiceDate ? formatDateOnly(tentativeServiceDate) : '—'}
        />
        <Row label="Validez" value={`${validityDays} días`} />
      </InfoCard>
    </div>
  )
}
