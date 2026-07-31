package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ServiceEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Repositorio de la bitacora del viaje. */
@ApplicationScoped
public class ServiceEventRepository implements PanacheRepositoryBase<ServiceEvent, Long> {
}
