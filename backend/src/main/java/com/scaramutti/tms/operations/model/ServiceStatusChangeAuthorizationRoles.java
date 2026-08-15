package com.scaramutti.tms.operations.model;

import java.util.Set;

/**
 * Los roles que OPERAN el viaje: los mismos cuatro que deja entrar la anotacion del endpoint de
 * transiciones.
 *
 * <p>Vive aparte del componente que la usa para que se pueda leer sin levantar el contenedor —el
 * test que fija que la lista no crezca sin decidir quien reabre no necesita un usuario— y para
 * dejar en un solo lugar el dato que hoy esta duplicado con la anotacion.
 */
public final class ServiceStatusChangeAuthorizationRoles {

    public static final Set<String> OPERATING_ROLES =
        Set.of("admin", "general_manager", "operations_manager", "dispatcher");

    private ServiceStatusChangeAuthorizationRoles() {}
}
