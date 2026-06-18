import type { QuotationStatus, QuotationType } from '../../../api'
import { QUOTATION_STATUS_PRESENTATION } from '../status/quotationStatusPresentation'

/** Etiquetas es-PE para el tipo de cotización (el contrato usa códigos en inglés). */
export const QUOTATION_TYPE_LABELS: Record<QuotationType, string> = {
  TRANSPORTE: 'Transporte',
  ALQUILER: 'Alquiler',
}

/**
 * Etiquetas es-PE para el estado de cotización. DERIVADAS del mapa único
 * (`QUOTATION_STATUS_PRESENTATION`) para no duplicar strings ni divergir: el label vive
 * en un solo lugar. Cubre los 5 estados (el `Record` lo garantiza en compilación).
 */
export const QUOTATION_STATUS_LABELS = Object.fromEntries(
  (Object.keys(QUOTATION_STATUS_PRESENTATION) as QuotationStatus[]).map((status) => [
    status,
    QUOTATION_STATUS_PRESENTATION[status].label,
  ]),
) as Record<QuotationStatus, string>
