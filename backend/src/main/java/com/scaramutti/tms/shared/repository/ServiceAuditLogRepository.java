package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ServiceAuditLog;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Repositorio de la auditoria del servicio. */
@ApplicationScoped
public class ServiceAuditLogRepository implements PanacheRepositoryBase<ServiceAuditLog, Long> {
}
