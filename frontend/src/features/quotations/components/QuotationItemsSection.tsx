import { Fragment } from 'react'
import { Badge } from '../../../shared/ui/Badge'
import { cn } from '../../../shared/utils/cn'
import { formatCurrency } from '../../../shared/utils/formatters'
import { isIntegralItem, itemSubtext, itemTotalWithIgv } from '../utils/quotationItemFormat'
import type { QuotationItemResponse } from '../../../api'

interface QuotationItemsSectionProps {
  items: QuotationItemResponse[]
  currencyCode: string
  subtotal: number
  igv: number
}

const TH = 'px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500'
const TD = 'px-3 py-2.5 align-top text-sm'

/** Fila de un ítem root (facturable): muestra precio al cliente y total con IGV. */
function RootRow({ item, currencyCode }: { item: QuotationItemResponse; currencyCode: string }) {
  const integral = isIntegralItem(item)
  const subtext = itemSubtext(item)
  return (
    <tr className={cn('border-t border-slate-100', integral && 'bg-blue-50')}>
      <td className={cn(TD, 'text-slate-500')}>{item.itemNumber}</td>
      <td className={TD}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-slate-900">{item.serviceType.name}</span>
          {integral && <Badge variant="info">Integral</Badge>}
        </div>
        {subtext && <p className="mt-0.5 text-xs text-slate-500">{subtext}</p>}
        {item.insuredAmount != null && (
          <p className="mt-0.5 text-xs text-slate-500">
            Valor asegurado: {formatCurrency(item.insuredAmount, currencyCode)}
          </p>
        )}
        {item.observations && <p className="mt-0.5 text-xs text-slate-500">{item.observations}</p>}
      </td>
      <td className={cn(TD, 'text-right tabular-nums')}>{item.quantity}</td>
      <td className={cn(TD, 'text-right tabular-nums')}>
        {item.unitPrice != null ? formatCurrency(item.unitPrice, currencyCode) : '—'}
      </td>
      <td className={cn(TD, 'text-right tabular-nums')}>{formatCurrency(item.subtotal, currencyCode)}</td>
      <td className={cn(TD, 'text-right tabular-nums text-slate-500')}>{`${item.igvPercentage}%`}</td>
      <td className={cn(TD, 'text-right font-semibold tabular-nums text-slate-900')}>
        {formatCurrency(itemTotalWithIgv(item), currencyCode)}
      </td>
    </tr>
  )
}

/** Fila de un hijo del Servicio Integral: precio interno de referencia (no
 * facturable). P. Neto/IGV en "—" porque no entra al total de la cotización. */
function ChildRow({ item, currencyCode }: { item: QuotationItemResponse; currencyCode: string }) {
  return (
    <tr className="border-t border-dashed border-slate-100">
      <td className={TD} />
      <td className={cn(TD, 'pl-6')}>
        <span className="text-slate-400" aria-hidden="true">
          ↳
        </span>
        <span className="ml-1.5 font-medium text-slate-400">{item.itemNumber}</span>
        <span className="ml-2 text-slate-700">{item.serviceType.name}</span>
      </td>
      <td className={cn(TD, 'text-right tabular-nums')}>{item.quantity}</td>
      <td className={cn(TD, 'text-right tabular-nums text-slate-600')}>
        {item.internalReferencePrice != null
          ? formatCurrency(item.internalReferencePrice, currencyCode)
          : '—'}
      </td>
      <td className={cn(TD, 'text-right text-slate-400')}>—</td>
      <td className={cn(TD, 'text-right text-slate-400')}>—</td>
      <td className={cn(TD, 'text-right text-slate-400')}>—</td>
    </tr>
  )
}

/** Tabla de ítems con la jerarquía del Servicio Integral y el subtotal/IGV al pie.
 * El total con IGV por ítem se calcula en el front (el contrato da el neto). */
export function QuotationItemsSection({ items, currencyCode, subtotal, igv }: QuotationItemsSectionProps) {
  return (
    <section>
      <h2 className="text-base font-semibold text-slate-900">Detalle de ítems</h2>
      <div className="mt-3 overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full">
          <caption className="sr-only">Detalle de ítems de la cotización</caption>
          <thead className="bg-slate-50">
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
                IGV
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
          <tfoot className="border-t border-slate-200 bg-slate-50">
            <tr>
              <td colSpan={5} />
              <td className={cn(TD, 'text-right font-medium text-slate-500')}>Subtotal</td>
              <td className={cn(TD, 'text-right tabular-nums text-slate-900')}>
                {formatCurrency(subtotal, currencyCode)}
              </td>
            </tr>
            <tr>
              <td colSpan={5} />
              <td className={cn(TD, 'text-right font-medium text-slate-500')}>IGV</td>
              <td className={cn(TD, 'text-right tabular-nums text-slate-900')}>
                {formatCurrency(igv, currencyCode)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  )
}
