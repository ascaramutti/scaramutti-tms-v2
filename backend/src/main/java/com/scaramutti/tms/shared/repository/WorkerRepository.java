package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Worker;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class WorkerRepository implements PanacheRepositoryBase<Worker, Integer> {
}
