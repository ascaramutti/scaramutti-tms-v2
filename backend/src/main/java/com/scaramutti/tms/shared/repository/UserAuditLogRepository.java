package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.UserAuditLog;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Rastro de las cuentas. Sin lecturas propias A PROPOSITO, como el de trabajadores: el historial
 * todavia no se muestra, y una consulta "por las dudas" abriria esas filas antes de que exista la
 * pantalla que decide quien puede verlas.
 */
@ApplicationScoped
public class UserAuditLogRepository implements PanacheRepositoryBase<UserAuditLog, Long> {
}
