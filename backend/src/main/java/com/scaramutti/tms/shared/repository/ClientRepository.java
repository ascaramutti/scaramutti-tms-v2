package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.entity.Client_;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ClientRepository implements PanacheRepositoryBase<Client, Integer> {

    public boolean existsByRuc(String ruc) {
        return count(Client_.RUC + " = ?1", ruc) > 0;
    }

    public boolean existsByName(String name) {
        return count(Client_.NAME + " = ?1", name) > 0;
    }
}
