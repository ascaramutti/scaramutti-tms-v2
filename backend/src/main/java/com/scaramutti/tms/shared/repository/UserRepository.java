package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.User_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Repository de usuarios. Vive en `shared` porque otros modulos lo usaran
 * (created_by, updated_by, audits, etc.). Auth lo consume para login.
 */
@ApplicationScoped
public class UserRepository implements PanacheRepositoryBase<User, Integer> {

    /**
     * Busca un usuario por username. Devuelve Optional vacio si no existe.
     * Usa singleResultOptional() para detectar bugs/corrupcion de data:
     * si por alguna razon hubiera dos usuarios con el mismo username,
     * lanza NonUniqueResultException en vez de silenciosamente tomar el primero.
     */
    public Optional<User> findByUsername(String username) {
        return find(User_.USERNAME, username).singleResultOptional();
    }

    /**
     * El usuario de un trabajador, o vacio. Hay a lo sumo uno: la columna es unica.
     *
     * <p>NO filtra por vigente a proposito. Quien pregunta esto esta decidiendo si un cambio de
     * cargo dejaria una cuenta con un rol sin permisos, y una cuenta apagada tambien se puede
     * volver a encender: filtrarla aca dejaria armada exactamente la cuenta rota que esa regla
     * existe para evitar.
     */
    public Optional<User> findByWorkerIdOptional(Integer workerId) {
        return find("worker.id", workerId).singleResultOptional();
    }

    /**
     * El id del trabajador de una cuenta, como valor suelto y SIN cargar la cuenta. Quien lo pide
     * va a bloquear esa fila y recien despues leer la cuenta: si la cuenta ya estuviera en memoria,
     * esa lectura devolveria la copia de antes de esperar y no la confirmada.
     */
    public Optional<Integer> findWorkerIdByUserId(Integer userId) {
        return getEntityManager()
            .createQuery("SELECT u.worker.id FROM User u WHERE u.id = :userId", Integer.class)
            .setParameter("userId", userId).getResultStream().findFirst();
    }

}
