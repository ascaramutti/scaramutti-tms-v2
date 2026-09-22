package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.workers.WorkersError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * La regla del organigrama, en UN solo lugar: quien escribe actua solo sobre cargos de nivel
 * estrictamente MENOR al suyo, y el administrador esta exento.
 *
 * <p>Vive sola y no adentro del servicio porque la van a usar las tres escrituras del modulo
 * (el alta, la edicion y el cambio de estado) y tienen que decidir igual: dos copias de esta
 * regla es la forma en que una jerarquia se pudre. El alta pregunta por el cargo del cuerpo;
 * la edicion va a preguntar por el actual y por el nuevo; el cambio de estado, por el actual.
 * Esta clase no sabe cual de las tres la llama, y por eso no tiene que cambiar cuando lleguen.
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
     *         de alcance, o el 403 comun si la sesion no resuelve a un usuario.
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
     * El usuario de la sesion, con su rol, leido de la base.
     *
     * <p>Que el id del token no resuelva a un usuario no es un caso de negocio sino una sesion
     * que ya no corresponde a nadie, asi que sale por el 403 comun de la aplicacion y no por un
     * codigo del modulo: no es que el cargo este fuera de alcance, es que no hay actor.
     *
     * <p>Y tiene que estar VIGENTE, no solo existir. Dar de baja a un usuario apaga su fila pero
     * no revoca su token: el inicio de sesion y la renovacion ya lo frenan, pero el token que
     * tenia en la mano sigue valiendo hasta que vence. Sin este filtro, alguien dado de baja
     * puede seguir dando de alta personas durante esa ventana, y encima firma con su id las
     * columnas de auditoria y la bitacora. La fila ya esta cargada aca, asi que mirarla no
     * cuesta una consulta mas.
     */
    private User requireActor() {
        return userRepository.findByIdOptional(currentUser.requireId())
            .filter(user -> Boolean.TRUE.equals(user.isActive))
            .orElseThrow(CommonError.FORBIDDEN::toException);
    }
}
