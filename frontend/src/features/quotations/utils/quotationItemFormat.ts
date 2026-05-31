import type { QuotationItemResponse } from '../../../api'

/** Un ítem es el Servicio Integral si su tipo de servicio tiene code `INT`. */
export function isIntegralItem(item: QuotationItemResponse): boolean {
  return item.serviceType.code === 'INT'
}

// Etiquetas es-PE para la categoría (`kind`) del tipo de servicio. El contrato
// tipa `kind` como string; ante un valor inesperado degradamos al valor crudo.
const SERVICE_KIND_LABELS: Record<string, string> = {
  SERVICIO: 'Servicio',
  ALQUILER: 'Alquiler',
  COMPLEMENTARIO: 'Complementario',
  INTEGRAL: 'Integral',
}

export function serviceKindLabel(kind: string): string {
  return SERVICE_KIND_LABELS[kind] ?? kind
}

/**
 * Dimensiones "L × A × H m" solo si las tres están presentes; `null` si falta
 * alguna (evita mostrar parciales como "5 × — × — m"). Los datos del catálogo
 * traen las tres o ninguna.
 */
export function formatDimensions(item: QuotationItemResponse): string | null {
  const { lengthMeters, widthMeters, heightMeters } = item
  if (lengthMeters == null || widthMeters == null || heightMeters == null) {
    return null
  }
  return `${lengthMeters} × ${widthMeters} × ${heightMeters} m`
}

/** Peso formateado "1,250 kg" (es-PE); `null` si no hay peso. */
export function formatWeight(weightKg: number | null | undefined): string | null {
  if (weightKg == null) {
    return null
  }
  return `${new Intl.NumberFormat('es-PE').format(weightKg)} kg`
}

/** Total con IGV de un ítem root (P. Neto + IGV). El contrato da `subtotal`
 * (neto) y `igvPercentage`; el total con impuesto se calcula en el front. */
export function itemTotalWithIgv(item: QuotationItemResponse): number {
  return item.subtotal * (1 + item.igvPercentage / 100)
}

/** Subtexto descriptivo de un ítem: tipo de carga · peso · dimensiones. `null`
 * si no hay ninguno. */
export function itemSubtext(item: QuotationItemResponse): string | null {
  const parts: string[] = []
  if (item.cargoType) parts.push(item.cargoType.name)
  const weight = formatWeight(item.weightKg)
  if (weight) parts.push(weight)
  const dimensions = formatDimensions(item)
  if (dimensions) parts.push(dimensions)
  return parts.length > 0 ? parts.join(' · ') : null
}
