import { Fragment } from 'react'
import { Badge } from '../../../shared/ui/Badge'
import { cn } from '../../../shared/utils/cn'
import { formatCurrency } from '../../../shared/utils/formatters'
import { isIntegralItem, itemIgvAmount, itemSubtext, itemTotalWithIgv } from '../utils/quotationItemFormat'
import type { QuotationItemResponse } from '../../../api'
import { Card } from '../../../shared/ui/Card'

interface QuotationItemsSectionProps {
  items: QuotationItemResponse[]
  currencyCode: string
  subtotal: number
  igv: number
}

const TH = 'px-3 py-2 text-xs font-semibold uppercase tracking-wide text-fg-muted'
const TD = 'px-3 py-2.5 align-top text-sm'

/** Fila de un ítem root (facturable): muestra precio al cliente y total con IGV. */
function RootRow({ item, currencyCode }: { item: QuotationItemResponse; currencyCode: string }) {
  const integral = isIntegralItem(item)
  const subtext = itemSubtext(item)
  return (
    <tr className={cn('border-t border-border', integral && 'bg-accent-soft')}>
      <td className={cn(TD, 'text-fg-muted')}>{item.displayLabel}</td>
      <td className={TD}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-fg">{item.serviceType.name}</span>
          {integral && <Badge variant="info">Integral</Badge>}
        </div>
        {subtext && <p className="mt-0.5 text-xs text-fg-muted">{subtext}</p>}
        {item.insuredAmount != null && (
          <p className="mt-0.5 text-xs text-fg-muted">
            Valor asegurado: {formatCurrency(item.insuredAmount, currencyCode)}
          </p>
        )}
        {item.observations && <p className="mt-0.5 text-xs text-fg-muted">{item.observations}</p>}
      </td>
      <td className={cn(TD, 'text-right tabular-nums')}>{item.quantity}</td>
      <td className={cn(TD, 'text-right tabular-nums')}>
        {item.unitPrice != null ? formatCurrency(item.unitPrice, currencyCode) : '—'}
      </td>
      <td className={cn(TD, 'text-right tabular-nums')}>{formatCurrency(item.subtotal, currencyCode)}</td>
      <td className={cn(TD, 'text-right tabular-nums text-fg-muted')}>
        {formatCurrency(itemIgvAmount(item), currencyCode)}
      </td>
      <td className={cn(TD, 'text-right font-semibold tabular-nums text-fg')}>
        {formatCurrency(itemTotalWithIgv(item), currencyCode)}
      </td>
    </tr>
  )
}

/** Fila de un hijo del Servicio Integral: precio interno de referencia (no
 * facturable). P. Neto/IGV en "—" porque no entra al total de la cotización. */
function ChildRow({ item, currencyCode }: { item: QuotationItemResponse; currencyCode: string }) {
  // Qué transporta/incluye el componente (tipo de carga · peso · dimensiones). Clave para que
  // el Integral sirva de referencia: sin esto no se sabe qué se cargó en el hijo de transporte.
  const subtext = itemSubtext(item)
  return (
    <tr className="border-t border-dashed border-border">
      <td className={TD} />
      <td className={cn(TD, 'pl-6')}>
        <div>
          <span className="text-fg-subtle" aria-hidden="true">
            ↳
          </span>
          <span className="ml-1.5 font-medium text-fg-subtle">{item.displayLabel}</span>
          <span className="ml-2 text-fg-body">{item.serviceType.name}</span>
        </div>
        {subtext && <p className="ml-7 mt-0.5 text-xs text-fg-muted">{subtext}</p>}
      </td>
      <td className={cn(TD, 'text-right tabular-nums')}>{item.quantity}</td>
      <td className={cn(TD, 'text-right tabular-nums text-fg-body')}>
        {item.internalReferencePrice
          ? formatCurrency(item.internalReferencePrice, currencyCode)
          : '—'}
      </td>
      <td className={cn(TD, 'text-right text-fg-subtle')}>—</td>
      <td className={cn(TD, 'text-right text-fg-subtle')}>—</td>
      <td className={cn(TD, 'text-right text-fg-subtle')}>—</td>
    </tr>
  )
}

/** Tabla de ítems con la jerarquía del Servicio Integral y el subtotal/IGV al pie.
 * El total con IGV por ítem se calcula en el front (el contrato da el neto). */
export function QuotationItemsSection({ items, currencyCode, subtotal, igv }: QuotationItemsSectionProps) {
  // El IGV es uniforme en la cotización y viene del backend (config en el wizard, snapshot en
  // el detalle): el % va en la cabecera y el monto por fila. Sin fallback hardcodeado.
  const igvPercent = items[0]?.igvPercentage
  return (
    <section>
      <h2 className="text-base font-semibold text-fg">Detalle de ítems</h2>
      <Card padding="none" className="mt-3 overflow-x-auto">
        <table className="min-w-full">
          <caption className="sr-only">Detalle de ítems de la cotización</caption>
          <thead className="bg-surface-subtle">
            <tr>
              <th scope="col" className={cn(TH, 'text-left')}>
                #
              </th>
              <th scope="col" className={cn(TH, 'text-left')}>
                Descripción
              </th>
              <th scope="col" className={cn(TH, 'text-right')}>
                Cant.
              </th>
              <th scope="col" className={cn(TH, 'text-right')}>
                P. Unit.
              </th>
              <th scope="col" className={cn(TH, 'text-right')}>
                P. Neto
              </th>
              <th scope="col" className={cn(TH, 'text-right')}>
                {igvPercent != null ? `IGV (${igvPercent}%)` : 'IGV'}
              </th>
              <th scope="col" className={cn(TH, 'text-right')}>
                Total
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <Fragment key={item.id}>
                <RootRow item={item} currencyCode={currencyCode} />
                {isIntegralItem(item) &&
                  item.children?.map((child) => (
                    <ChildRow key={child.id} item={child} currencyCode={currencyCode} />
                  ))}
              </Fragment>
            ))}
          </tbody>
          <tfoot className="border-t border-border bg-surface-subtle">
            <tr>
              <td colSpan={5} />
              <td className={cn(TD, 'text-right font-medium text-fg-muted')}>Subtotal</td>
              <td className={cn(TD, 'text-right tabular-nums text-fg')}>
                {formatCurrency(subtotal, currencyCode)}
              </td>
            </tr>
            <tr>
              <td colSpan={5} />
              <td className={cn(TD, 'text-right font-medium text-fg-muted')}>IGV</td>
              <td className={cn(TD, 'text-right tabular-nums text-fg')}>
                {formatCurrency(igv, currencyCode)}
              </td>
            </tr>
          </tfoot>
        </table>
      </Card>
    </section>
  )
}
