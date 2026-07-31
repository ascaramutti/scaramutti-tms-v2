package com.scaramutti.tms.sharedcatalogs.model;

import java.util.Locale;

/**
 * Disponibilidad de un recurso de flota: la comparten las unidades (GET /fleet-units) y los
 * conductores (GET /drivers), por eso vive en {@code sharedcatalogs/model/} y no bajo el
 * paquete de uno de los dos.
 *
 * <p>En la BD es el catalogo {@code public.resource_statuses} (tabla de v1, con CRUD nominal
 * que nadie usa: sus tres filas son fijas desde el arranque). Para la API es un dominio
 * CERRADO en mayusculas, como el resto de los enums: el puente entre ambos es el NOMBRE del
 * catalogo, no su id, que difiere entre ambientes.
 */
public enum FleetResourceStatus {

    AVAILABLE,
    MAINTENANCE,
    NOT_AVAILABLE;

    /**
     * Traduce el nombre del catalogo ({@code available}, {@code maintenance},
     * {@code not_available}) al enum. {@code null} entra y sale como {@code null}: hay
     * recursos sin disponibilidad en la respuesta (las escoltas, que no se asignan a viajes).
     *
     * <p>Un nombre fuera del dominio REVIENTA en vez de devolver null: si alguien agrega una
     * fila al catalogo de v1, el endpoint tiene que gritar y no servir en silencio una unidad
     * sin estado, que la pantalla de asignacion leeria como "sin dato".
     */
    public static FleetResourceStatus fromCatalogName(String catalogName) {
        if (catalogName == null) {
            return null;
        }
        try {
            return valueOf(catalogName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "public.resource_statuses has a status outside the API domain: " + catalogName, e);
        }
    }
}
