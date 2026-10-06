package com.scaramutti.tms.workers.model;

import java.util.Locale;

/**
 * Si un cargo lleva ficha de conductor: obligatoria, opcional o ninguna.
 *
 * <p>Vive en el modulo y no junto a la entidad de rol porque es dominio de trabajadores; en
 * la base es texto con su restriccion, y el modulo lo convierte al leerlo.
 */
public enum DriverProfileMode {

    REQUIRED,
    OPTIONAL,
    NONE;

    /**
     * Traduce el texto de la columna al enum.
     *
     * <p>Un valor fuera del dominio REVIENTA en vez de devolver nulo: servir una modalidad
     * vacia haria que el formulario decida mal si pedir la ficha, y eso se descubre recien
     * cuando alguien no puede dar de alta a un conductor. La restriccion de la columna ya
     * cierra el dominio, asi que llegar aca con otra cosa significa que alguien la toco por
     * fuera de una migracion.
     */
    public static DriverProfileMode fromColumn(String driverProfile) {
        try {
            return valueOf(driverProfile.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                "public.roles has a driver_profile outside the API domain: " + driverProfile, e);
        }
    }
}
