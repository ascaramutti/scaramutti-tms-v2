import type { ReactNode } from 'react'
import { Building2, FileText, MapPin, type LucideIcon } from 'lucide-react'
import { formatDateOnly } from '../../../shared/utils/formatters'

interface QuotationSummaryCardProps {
  /** Nombre del cliente; ausente → "Sin cliente seleccionado" (resumen antes de elegir). */
  clientName: string | null | undefined
  clientRuc: string | null | undefined
  contactName: string | null | undefined
  contactPhone: string | null | undefined
  quotationType: string
  origin: string | null | undefined
  destination: string | null | undefined
  currencyCode: string | null | undefined
  paymentTermName: string | null | undefined
  validityDays: number | null | undefined
  tentativeServiceDate: string | null | undefined
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
 * Tres tarjetas de cabecera de una cotización: Cliente · Ruta · Condiciones comerciales.
 * Componente de presentación con props PLANAS para servir tanto al Detalle (que las arma desde
 * la `QuotationResponse`) como al Resumen del wizard (que las arma desde el form vía
 * {@link Step4SummaryCard}). Defensivo: `clientName` null = "Sin cliente seleccionado";
 * valores comerciales ausentes = "—".
 */
export function QuotationSummaryCard({
  clientName,
  clientRuc,
  contactName,
  contactPhone,
  quotationType,
  origin,
  destination,
  currencyCode,
  paymentTermName,
  validityDays,
  tentativeServiceDate,
}: QuotationSummaryCardProps) {
  const hasRoute = quotationType === 'TRANSPORTE' && !!origin && !!destination

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
      <InfoCard icon={Building2} title="Cliente">
        {clientName ? (
          <>
            <p className="text-sm font-semibold text-slate-900">{clientName}</p>
            {clientRuc && <p className="text-xs text-slate-500">RUC {clientRuc}</p>}
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
        <Row label="Moneda" value={currencyCode ?? '—'} />
        <Row label="Pago" value={paymentTermName ?? '—'} />
        <Row
          label="Fecha tentativa"
          value={tentativeServiceDate ? formatDateOnly(tentativeServiceDate) : '—'}
        />
        <Row label="Validez" value={validityDays ? `${validityDays} días` : '—'} />
      </InfoCard>
    </div>
  )
}
