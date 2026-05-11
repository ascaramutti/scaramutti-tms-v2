package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Role;
import com.scaramutti.tms.shared.entity.Role_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class RoleRepository implements PanacheRepositoryBase<Role, Integer> {

    public Optional<Role> findByName(String name) {
        return find(Role_.NAME, name).singleResultOptional();
    }
}
