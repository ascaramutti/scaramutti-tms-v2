package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.workers.WorkersError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * La regla del organigrama, en UN solo lugar: quien escribe actua solo sobre cargos de nivel
 * estrictamente MENOR al suyo, y el administrador esta exento.
 *
 * <p>Vive sola y no adentro del servicio porque la usan todas las escrituras del modulo y
 * tienen que decidir igual: dos copias de esta regla es la forma en que una jerarquia se pudre.
 * El alta pregunta por el cargo del cuerpo; la edicion pregunta por el actual, por el nuevo y
 * por el de la cuenta; el cambio de estado, por el actual y el de la cuenta. Esta clase no sabe
 * cual de ellas la llama, y por eso no tuvo que cambiar cuando llegaron las otras.
 *
 * <p>EL NIVEL DEL ACTOR SE LEE DE LA BASE, no del token, y no es un detalle: el token no lleva
 * el nivel, y su grupo de rol puede estar viejo, porque cambiar el cargo de un trabajador con
 * usuario le cambia el rol sin reemitirle el token. Una regla de autorizacion que se apoye en
 * un dato que puede estar vencido decide mal justo cuando importa.
 */
@ApplicationScoped
public class WorkerRankPolicy {

    /** El unico rol exento: actua sobre cualquier cargo, incluido el suyo. */
    private static final String EXEMPT_ROLE = "admin";

    /**
     * Los roles que escriben trabajadores: la misma lista que el {@code @RolesAllowed} de cada
     * escritura del recurso, que mira el token. Esta mira la fila; un test compara las dos.
     */
    static final Set<String> WRITE_ROLES =
        Set.of("admin", "general_manager", "operations_manager", "finance_manager");

    @Inject CurrentUser currentUser;
    @Inject UserRepository userRepository;

    /**
     * Exige que quien tiene la sesion pueda actuar sobre un trabajador de este cargo.
     *
     * <p>Mismo nivel NO alcanza: la regla es estrictamente menor. Eso deja afuera, a proposito,
     * que alguien de nivel 2 de de alta a otro de nivel 2, y que alguien se edite a si mismo,
     * porque su propio cargo es de su propio nivel; solo el administrador corrige su ficha.
     *
     * @throws com.scaramutti.tms.shared.exception.ApiException WRK-006 si el cargo esta fuera
     *         de alcance, o el 403 comun si quien tiene la sesion no esta habilitado para escribir.
     */
    public void assertCanActOn(Role targetRole) {
        User actor = requireActor();
        if (EXEMPT_ROLE.equals(actor.role.name)) {
            return;
        }
        if (targetRole.level >= actor.role.level) {
            throw WorkersError.ROLE_OUT_OF_RANK.toException();
        }
    }

    /**
     * Exige que quien tiene la sesion no desactive SU PROPIO trabajador, tampoco el
     * administrador (WRK-010). No es rango: el administrador esta exento del rango y esta regla
     * es justamente para el. Para cualquier otro corre ANTES que el rango, porque su propio
     * cargo es de su propio nivel y el rango lo frenaria con un codigo que no dice por que.
     */
    public void assertIsNotOwnWorker(Worker targetWorker) {
        User actor = requireActor();
        if (actor.worker.id.equals(targetWorker.id)) {
            throw WorkersError.SELF_DEACTIVATION.toException();
        }
    }

    /**
     * El usuario de la sesion, leido de la base, y habilitado HOY para escribir: cuenta vigente,
     * uno de los cuatro roles de escritura en su fila y su trabajador activo. El token vale hasta
     * que vence y su rol puede ser viejo: sin esto, a alguien dado de baja o bajado de cargo le
     * quedaba una ventana para escribir y firmar el rastro. Sin actor habilitado no es un caso
     * del modulo sino el 403 comun: no es que el cargo este fuera de alcance, es que no hay quien.
     * Devuelve siempre un actor con trabajador: los metodos de esta clase cuentan con eso.
     */
    private User requireActor() {
        return userRepository.findByIdOptional(currentUser.requireId())
            .filter(user -> Boolean.TRUE.equals(user.isActive))
            .filter(user -> WRITE_ROLES.contains(user.role.name))
            .filter(user -> user.worker != null && Boolean.TRUE.equals(user.worker.isActive))
            .orElseThrow(CommonError.FORBIDDEN::toException);
    }

    /**
     * Exige que quien tiene la sesion no se cambie el cargo a SI MISMO, en NINGUNA de las dos
     * filas que una edicion mueve: la del trabajador y la de su cuenta del sistema.
     *
     * <p>No es rango, y por eso es un metodo aparte y no una rama del de arriba: el rango compara
     * niveles y tiene al administrador exento, y esta regla es justamente sobre el administrador,
     * el unico que el rango deja pasar. Para cualquier otro, su propio cargo es de su propio
     * nivel y el rango ya lo freno antes; este metodo existe para el que no.
     *
     * <p>Lo que protege: un administrador que se baja el cargo a si mismo puede dejar a la
     * empresa sin administradores por esta API, sin que ninguna otra regla lo note. Editarse el
     * resto de la ficha sigue permitido; lo unico cerrado es el cargo.
     *
     * <p>MIRA LAS DOS FILAS PORQUE LA EDICION ESCRIBE LAS DOS, y lo que otorga los permisos es
     * la de la CUENTA: el token sale de ahi. Mientras las dos coincidan, la segunda comparacion
     * no cambia ninguna respuesta; si divergen —y nada en la base lo impide— mirar solo la del
     * trabajador deja pasar el cuerpo que reenvia el cargo del trabajador sin cambios mientras
     * la cascada mueve el de la cuenta. Con eso, un administrador se degrada solo y recibe 200.
     * Basta con preguntar por la propia ficha para alcanzar las dos filas: la columna que liga
     * una cuenta a su trabajador es UNICA, asi que un trabajador tiene a lo sumo una cuenta y es
     * la del actor.
     *
     * @throws com.scaramutti.tms.shared.exception.ApiException si el trabajador es el de la
     *         sesion y cambia el cargo de cualquiera de las dos filas.
     */
    public void assertCanChangeRoleOf(Worker targetWorker, User targetAccount, Role newRole) {
        User actor = requireActor();
        if (!actor.worker.id.equals(targetWorker.id)) {
            return;
        }
        boolean mueveElCargoDelTrabajador = !targetWorker.role.id.equals(newRole.id);
        boolean mueveElCargoDeLaCuenta =
            targetAccount != null && !targetAccount.role.id.equals(newRole.id);
        if (mueveElCargoDelTrabajador || mueveElCargoDeLaCuenta) {
            throw WorkersError.SELF_ROLE_CHANGE.toException();
        }
    }

}
