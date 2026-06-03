import { formatCurrency } from '../../../shared/utils/formatters'

interface QuotationTotalGeneralProps {
  total: number
  currencyCode: string
  /** Monto en letras (opcional). Si se pasa, se muestra bajo el total. */
  amountInWords?: string
}

/** Total general destacado de la cotización (acento azul del sistema). */
export function QuotationTotalGeneral({ total, currencyCode, amountInWords }: QuotationTotalGeneralProps) {
  return (
    <div className="rounded-xl bg-blue-600 px-8 py-5 text-right text-white shadow-md">
      <p className="text-xs font-medium uppercase tracking-wide text-blue-100">Total general</p>
      <p className="mt-1 text-3xl font-bold tabular-nums">{formatCurrency(total, currencyCode)}</p>
      {amountInWords && <p className="mt-1.5 text-xs font-medium text-blue-100">{amountInWords}</p>}
    </div>
  )
}
