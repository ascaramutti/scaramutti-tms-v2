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

/**
 * La regla del organigrama, en UN solo lugar: quien escribe actua solo sobre cargos de nivel
 * estrictamente MENOR al suyo, y el administrador esta exento.
 *
 * <p>Vive sola y no adentro del servicio porque la usan todas las escrituras del modulo y
 * tienen que decidir igual: dos copias de esta regla es la forma en que una jerarquia se pudre.
 * El alta pregunta por el cargo del cuerpo; la edicion pregunta por el actual, por el nuevo y
 * por el de la cuenta; el cambio de estado, cuando llegue, por el actual. Esta clase no sabe
 * cual de ellas la llama, y por eso no tuvo que cambiar cuando llego la segunda.
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
        // La comprobacion de nulo es DEFENSIVA y hoy inalcanzable: la columna que liga un usuario
        // a su trabajador es obligatoria. Se deja porque de ella depende una regla de
        // autorizacion, y el dia que esa columna admitiera nulos su ausencia seria un permiso
        // implicito; pero que nadie la lea como un caso real.
        if (actor.worker == null || !actor.worker.id.equals(targetWorker.id)) {
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
