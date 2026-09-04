import type { WarehousePurchaseInvoiceResponse } from '../../../api'
import { formatDate, formatDateOnly } from '../../../shared/utils/formatters'
import { Card } from '../../../shared/ui/Card'

interface EntryInfoCardsProps {
  invoice: WarehousePurchaseInvoiceResponse
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
 * Ficha de la entrada: los datos de la factura de compra y, si la factura fue
 * editada alguna vez, el rastro de la última edición (quién, cuándo y con qué
 * justificación). El proveedor y el N° de factura ya viven en el encabezado de
 * la pantalla, así que la ficha completa el resto sin repetir el título.
 */
export function EntryInfoCards({ invoice }: EntryInfoCardsProps) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card as="section" padding="md" aria-labelledby="entry-info-heading">
        <h2 id="entry-info-heading" className="text-sm font-semibold text-fg">
          Factura
        </h2>
        <dl className="mt-3 grid grid-cols-2 gap-3">
          <Field label="Proveedor" value={invoice.supplier.name} />
          <Field label="RUC" value={invoice.supplier.ruc ?? '—'} />
          <Field label="N° de guía" value={invoice.guideNumber ?? '—'} />
          <Field label="Fecha de factura" value={formatDateOnly(invoice.invoiceDate)} />
          <Field
            label="Moneda"
            value={`${invoice.currency.code} (${invoice.currency.symbol})`}
          />
          <Field
            label="Registró"
            value={`${invoice.registeredBy.fullName} · ${formatDate(invoice.createdAt)}`}
          />
        </dl>
      </Card>

      {invoice.lastEdit && (
        <Card as="section" padding="md" aria-labelledby="entry-lastedit-heading">
          <h2 id="entry-lastedit-heading" className="text-sm font-semibold text-fg">
            Última edición
          </h2>
          <dl className="mt-3 space-y-3">
            <Field
              label="Editó"
              value={`${invoice.lastEdit.by.fullName} · ${formatDate(invoice.lastEdit.at)}`}
            />
            <Field label="Motivo" value={invoice.lastEdit.reason} />
          </dl>
        </Card>
      )}
    </div>
  )
}
