package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Quien puede ver lo que se cobra por un viaje. La regla vive en el SERVIDOR y no en la interfaz:
 * esconder la columna en la pantalla no serviria, porque bastaria mirar la respuesta cruda.
 *
 * <p>Es un componente propio porque la aplican dos endpoints (el listado y el detalle) y va a
 * aplicarla un tercero (el reporte): tenerla en un solo lugar evita que se conteste distinto
 * segun por donde se pregunte, que es la forma en que estas reglas se pudren.
 */
@ApplicationScoped
public class ServicePriceVisibility {

    /**
     * Roles que SI ven precios. Es una lista positiva a proposito: un rol nuevo que nadie agregue
     * aca no hereda el permiso por accidente, que es el modo seguro de equivocarse.
     */
    private static final Set<String> PRICE_ROLES =
        Set.of("admin", "sales", "general_manager", "operations_manager");

    /**
     * Roles con VETO: no ven precios pase lo que pase. La regla del negocio dice que el despacho
     * nunca los ve, y eso es un veto, no una suma de permisos: si algun dia un usuario tuviera
     * dos roles, "tiene alguno que ve precios" le daria acceso y la regla dice lo contrario.
     */
    private static final Set<String> PRICE_BLIND_ROLES = Set.of("dispatcher");

    @Inject CurrentUser currentUser;

    /** El veto manda sobre la lista positiva: primero se pregunta quien NO puede ver precios. */
    public boolean includePrices() {
        return !currentUser.hasAnyRole(PRICE_BLIND_ROLES) && currentUser.hasAnyRole(PRICE_ROLES);
    }
}
