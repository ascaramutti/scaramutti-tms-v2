import type { WarehouseReportCut } from '../../../api'

export interface ReportCutMeta {
  value: WarehouseReportCut
  /** Nombre del corte en los tabs y en el encabezado del ranking. */
  label: string
  /**
   * Qué cuenta la columna `count` en este corte. No es cosmético: en
   * `BY_PRODUCT` el backend devuelve unidades retiradas, no movimientos.
   */
  countLabel: string
  /** Rótulo del total en soles/dólares (compras en BY_SUPPLIER, consumo en el resto). */
  amountLabel: (currency: 'soles' | 'dólares') => string
  /** Trozo del nombre del archivo exportado. */
  slug: string
}

/**
 * Metadata de los 4 cortes del reporte, en el orden en que se muestran.
 *
 * `BY_SUPPLIER` es el único que mide COMPRAS (facturas por su fecha de factura);
 * los otros tres miden CONSUMO (retiros activos por su fecha de retiro), así que
 * los rótulos de montos y de conteo cambian con el corte.
 */
export const REPORT_CUTS: readonly ReportCutMeta[] = [
  {
    value: 'BY_UNIT',
    label: 'Por unidad de flota',
    countLabel: 'retiros',
    amountLabel: (currency) => `Retirado en ${currency} (precio de compra ref.)`,
    slug: 'por-unidad',
  },
  {
    value: 'BY_PERIOD',
    label: 'Por semana',
    countLabel: 'retiros',
    amountLabel: (currency) => `Retirado en ${currency} (precio de compra ref.)`,
    slug: 'por-semana',
  },
  {
    value: 'BY_PRODUCT',
    label: 'Por producto',
    countLabel: 'unidades retiradas',
    amountLabel: (currency) => `Retirado en ${currency} (precio de compra ref.)`,
    slug: 'por-producto',
  },
  {
    value: 'BY_SUPPLIER',
    label: 'Compras por proveedor',
    countLabel: 'facturas',
    amountLabel: (currency) => `Comprado en ${currency}`,
    slug: 'por-proveedor',
  },
]

/** Metadata del corte dado. El enum viene del contrato, así que siempre resuelve. */
export function reportCutMeta(cut: WarehouseReportCut): ReportCutMeta {
  return REPORT_CUTS.find((meta) => meta.value === cut) ?? REPORT_CUTS[0]
}
