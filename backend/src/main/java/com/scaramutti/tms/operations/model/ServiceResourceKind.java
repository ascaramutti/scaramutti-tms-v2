package com.scaramutti.tms.operations.model;

/**
 * Los tres recursos que participan de un viaje. Es el discriminador con el que el conflicto de
 * asignacion dice CUAL de los pedidos esta ocupado.
 *
 * <p>No se reusa {@code FleetUnitKind}: ese enumera los subtipos de la FLOTA (tracto, carreta,
 * escolta) y aca el conductor es uno de los tres, mientras que la escolta no participa de la
 * asignacion de un viaje. Son dos dominios que se parecen en dos de sus valores y difieren en el
 * tercero, que es la peor forma de parecerse.
 *
 * <p>El orden en el que se lockean los recursos NO sale de aca: lo fija
 * {@code ServiceResourceLockKeys}, ordenando por el TEXTO de la clave. Hoy las dos secuencias
 * coinciden porque el texto empieza por el nombre del tipo y estos tres estan en orden
 * alfabetico, pero es coincidencia: un tipo nuevo declarado en cualquier posicion las separaria.
 * Si hace falta que coincidan, hay que decirlo en un solo lugar, y ese lugar es el otro.
 */
public enum ServiceResourceKind {
    DRIVER,
    TRACTOR,
    TRAILER
}
