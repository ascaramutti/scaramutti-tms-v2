package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.operations.model.ServiceStatusChangeAuthorizationRoles;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
import com.scaramutti.tms.shared.exception.CommonError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Quien puede pedir CADA transicion. El endpoint deja entrar a cuatro roles; esto decide, ya
 * adentro, cuales de los cinco destinos puede pedir el que entro.
 *
 * <p>Son DOS reglas de negocio, no una, y esta clase aplica las dos. La primera: el despacho
 * opera el viaje pero no decide matarlo ni descartarlo (RN-OP7), asi que inicia y finaliza pero no
 * cancela ni elimina. La segunda: deshacer una de esas dos salidas es mas acotado todavia, y ahi
 * queda afuera tambien la gerencia de OPERACIONES — reabrir es solo de {@code admin} y
 * {@code general_manager}.
 *
 * <p>Esta escrita como VETO y no como lista de permitidos, y la diferencia no es de estilo. Los
 * roles del token son un conjunto: con "estos roles pueden cancelar", a un usuario que sumara
 * despacho y gerencia le alcanzaria el segundo para pasar, y la regla quedaria diciendo lo
 * contrario de lo que significa. Con el veto, tener el rol vetado alcanza para que NO pase, sin
 * importar que mas tenga. Es el mismo molde —y el mismo motivo— que la visibilidad de precios.
 *
 * <p>Hoy la tabla de usuarios limita a un rol por persona, asi que la diferencia entre las dos
 * formas solo se ve con un token fabricado. Que hoy no sea alcanzable no la hace decorativa: la
 * regla se escribe una vez y sobrevive al dia en que un usuario tenga dos roles.
 */
@ApplicationScoped
public class ServiceStatusChangeAuthorization {

    /**
     * Los cuatro roles que operan el viaje, iguales a los de la anotacion del endpoint.
     *
     * <p>Se exige pertenecer a alguno ADEMAS del veto, y no es redundante: {@code hasAnyRole}
     * devuelve false cuando no hay claim de roles, y en una lista NEGATIVA ese false significa
     * "pasa". Hoy la anotacion garantiza que el claim traiga uno de los cuatro, pero si mañana el
     * acceso se resolviera por configuracion en vez de por anotacion, el veto se abriria solo
     * mientras las listas positivas del modulo seguirian cerradas. Un default que depende de quien
     * lo mire no es un default.
     */
    @Inject CurrentUser currentUser;

    /** 403 COM-003 si el que llama no opera viajes, o tiene un rol vetado para esta transicion. */
    public void requireCanRequest(ServiceStatusTransition transition) {
        if (!currentUser.hasAnyRole(ServiceStatusChangeAuthorizationRoles.OPERATING_ROLES)
                || currentUser.hasAnyRole(transition.vetoedRoles())) {
            throw CommonError.FORBIDDEN.toException();
        }
    }
}
