package com.scaramutti.tms.operations.model;

/**
 * Ambito del viaje. Dominio CERRADO sin catalogo administrable: vive como columna + CHECK en
 * la BD y como enum acá, no como tabla (no tiene CRUD ni atributos propios). El selector del
 * frontend sale de este mismo dominio, por eso no hay endpoint de ambitos.
 *
 * <p>El sistema anterior lo modelaba como tabla de 2 filas ({@code Local}/{@code Provincia});
 * el script de migracion de datos las mapea por nombre a estos valores.
 */
public enum TripScope {

    /** Viaje dentro de la ciudad. */
    LOCAL,

    /** Viaje interprovincial. */
    PROVINCIA
}
