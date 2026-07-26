package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.AuditLog;
import com.scaramutti.tms.warehouse.model.AuditChangeType;
import com.scaramutti.tms.warehouse.model.AuditEntityType;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Repositorio de auditoria de almacen. Vive en {@code shared/repository/} por
 * convencion. El {@code persist} viene de Panache; expone la lectura del ultimo
 * FIELD_EDIT que alimenta el {@code lastEdit} del detalle.
 */
@ApplicationScoped
public class AuditLogRepository implements PanacheRepositoryBase<AuditLog, Long> {

    /**
     * Ultimo FIELD_EDIT de una entidad (para el {@code lastEdit} del detalle). Ordena
     * por {@code loggedAt DESC, id DESC} (desempate por id: {@code logged_at} va a
     * micros y una edicion escribe varias filas en el mismo instante) y toma la primera
     * ({@code firstResultOptional}, no {@code singleResult}, porque hay varias filas por
     * edicion). {@code null} si nunca se edito.
     */
    public Optional<AuditLog> findLastFieldEdit(AuditEntityType entityType, Integer entityId) {
        return find(
            "entityType = ?1 and entityId = ?2 and changeType = ?3",
            Sort.by("loggedAt").descending().and("id").descending(),
            entityType.name(), entityId, AuditChangeType.FIELD_EDIT.name()
        ).firstResultOptional();
    }
}
