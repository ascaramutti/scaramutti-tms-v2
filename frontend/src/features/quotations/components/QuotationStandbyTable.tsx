import { Badge } from '../../../shared/ui/Badge'
import { formatCurrency } from '../../../shared/utils/formatters'
import type { QuotationItemResponse, QuotationStandbyCostResponse } from '../../../api'

interface QuotationStandbyTableProps {
  items: QuotationItemResponse[]
  currencyCode: string
}

interface StandbyEntry {
  item: QuotationItemResponse
  standby: QuotationStandbyCostResponse
}

/** Aplana root + hijos y devuelve los ítems que tienen stand-by, con el
 * stand-by ya estrechado (no nulo) para consumirlo sin re-chequear. */
function collectStandby(items: QuotationItemResponse[]): StandbyEntry[] {
  const result: StandbyEntry[] = []
  for (const item of items) {
    if (item.standby) result.push({ item, standby: item.standby })
    item.children?.forEach((child) => {
      if (child.standby) result.push({ item: child, standby: child.standby })
    })
  }
  return result
}

const TH = 'px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500'

/** Tabla de stand-by por ítem. No se renderiza si ningún ítem tiene stand-by. */
export function QuotationStandbyTable({ items, currencyCode }: QuotationStandbyTableProps) {
  const entries = collectStandby(items)
  if (entries.length === 0) {
    return null
  }

  return (
    <section>
      <h2 className="text-base font-semibold text-slate-900">Stand-By</h2>
      <div className="mt-3 overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full">
          <caption className="sr-only">Costos de stand-by por ítem</caption>
          <thead className="bg-slate-50">
            <tr>
              <th scope="col" className={`${TH} text-left`}>
                Ítem
              </th>
              <th scope="col" className={`${TH} text-right`}>
                Precio por día
              </th>
              <th scope="col" className={`${TH} text-center`}>
                Incluye IGV
              </th>
            </tr>
          </thead>
          <tbody>
            {entries.map(({ item, standby }) => (
              <tr key={item.id} className="border-t border-slate-100">
                <td className="px-3 py-2.5 text-sm text-slate-800">
                  Ítem {item.displayLabel} ({item.serviceType.name})
                </td>
                <td className="px-3 py-2.5 text-right text-sm tabular-nums text-slate-900">
                  {formatCurrency(standby.pricePerDay, currencyCode)}
                </td>
                <td className="px-3 py-2.5 text-center">
                  <Badge variant={standby.includesIgv ? 'success' : 'default'}>
                    {standby.includesIgv ? 'Sí' : 'No'}
                  </Badge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
