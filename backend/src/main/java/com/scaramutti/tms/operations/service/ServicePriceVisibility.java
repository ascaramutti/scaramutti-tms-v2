package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.exception.CommonError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Quien puede ver lo que se cobra por un viaje. La regla vive en el SERVIDOR y no en la interfaz:
 * esconder la columna en la pantalla no serviria, porque bastaria mirar la respuesta cruda.
 *
 * <p>Es un componente propio porque la aplican cuatro endpoints —el listado y el detalle omiten
 * los importes, el alta y la edicion no dejan escribirlos a ciegas— y va a aplicarla un quinto
 * (el reporte): tenerla en un solo lugar evita que se conteste distinto segun por donde se
 * pregunte, que es la forma en que estas reglas se pudren.
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

    /**
     * A quien no puede VER los importes tampoco se le deja escribirlos, ni al registrar ni al
     * editar. Los dos cuerpos EXIGEN el precio, asi que dejarlo pasar lo obligaria a mandar a
     * ciegas un valor que no puede leer: cada operacion suya pisaria el importe con una
     * adivinanza, y ademas podria adivinarlo por descarte mirando si la bitacora registro el
     * cambio. Ignorar el precio del cuerpo seria peor: dejaria un endpoint que miente sobre lo
     * que guardo.
     *
     * <p>Hace falta porque la lista de roles del endpoint es un O y esto es un VETO: el dia que
     * un usuario tenga dos roles, uno que sume despacho y ventas entraria por la lista.
     */
    public void requireCanSeePrices() {
        if (!includePrices()) {
            throw CommonError.FORBIDDEN.toException();
        }
    }
}
