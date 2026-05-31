import type { QuotationStatus, QuotationType } from '../../../api'

/** Etiquetas es-PE para el tipo de cotización (el contrato usa códigos en inglés). */
export const QUOTATION_TYPE_LABELS: Record<QuotationType, string> = {
  TRANSPORTE: 'Transporte',
  ALQUILER: 'Alquiler',
}

/** Etiquetas es-PE para el estado de cotización. */
export const QUOTATION_STATUS_LABELS: Record<QuotationStatus, string> = {
  DRAFT: 'Borrador',
  SENT: 'Enviada',
}
