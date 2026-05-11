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
}
