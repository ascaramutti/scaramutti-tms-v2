import type { FleetUnitKind, FleetUnitResponse } from '../../api'

/** Etiqueta en español de cada subtipo de unidad, para no mostrar el enum crudo. */
const KIND_LABEL: Record<FleetUnitKind, string> = {
  TRACTOR: 'Tracto',
  TRAILER: 'Carreta',
  ESCORT: 'Escolta',
}

/** "Tracto ABC-123": el subtipo primero, así el retiro dice a qué se cargó. */
export function fleetUnitLabel(fleetUnit: Pick<FleetUnitResponse, 'kind' | 'plate'>): string {
  return `${KIND_LABEL[fleetUnit.kind]} ${fleetUnit.plate}`
}

/**
 * Clave de identidad de una unidad: el par (kind, id). Un id suelto NO alcanza:
 * los subtipos numeran por separado y un tracto y una carreta pueden compartir id,
 * así que resolver por id solo devolvería la unidad equivocada (y el retiro se
 * cargaría al campo de flota incorrecto).
 */
export function fleetUnitKey(fleetUnit: Pick<FleetUnitResponse, 'kind' | 'id'>): string {
  return `${fleetUnit.kind}:${fleetUnit.id}`
}
