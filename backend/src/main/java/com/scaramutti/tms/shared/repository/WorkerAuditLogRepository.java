package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.WorkerAuditLog;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bitacora de trabajadores. Sin metodos de lectura A PROPOSITO: nadie la lee todavia, y sus
 * filas guardan datos personales historicos (documentos, telefonos y licencias anteriores).
 * Publicar una consulta "por las dudas" abriria eso antes de que exista la pantalla que decide
 * quien puede verlo.
 */
@ApplicationScoped
public class WorkerAuditLogRepository implements PanacheRepositoryBase<WorkerAuditLog, Long> {
}
