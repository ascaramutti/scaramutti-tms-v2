package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ServiceEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Repositorio de la bitacora del viaje. */
@ApplicationScoped
public class ServiceEventRepository implements PanacheRepositoryBase<ServiceEvent, Long> {

    /**
     * La bitacora de un viaje, del evento mas viejo al mas nuevo. El id desempata: dos lineas
     * escritas en la misma transaccion comparten el instante al microsegundo, y sin desempate el
     * orden entre ellas quedaria a criterio del motor.
     */
    public List<ServiceEvent> listByServiceIdOrderedByCreatedAt(long serviceId) {
        return list("serviceId = ?1 ORDER BY createdAt ASC, id ASC", serviceId);
    }
}
