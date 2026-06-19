package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.QuotationsError;
import com.scaramutti.tms.shared.entity.Condition;
import com.scaramutti.tms.shared.entity.QuotationCondition;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import com.scaramutti.tms.shared.repository.QuotationConditionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Valida y persiste las condiciones generales aplicadas a una cotizacion (junction
 * {@code cotizaciones.quotation_conditions}, ADR-009/010). Compartido entre crear y editar
 * — una sola fuente para la validacion de escritura y la persistencia, evita drift entre flujos.
 *
 * <p>Validacion de escritura — todas las condiciones seleccionadas deben estar activas (es la
 * "Opcion B" del ADR-010/RN-06; ahi esta la alternativa descartada). En {@link #validate}:
 * <ul>
 *   <li>ids duplicados en la seleccion → 400 {@code COM-001},</li>
 *   <li>{@code conditionId} inexistente en el catalogo → 400 {@code COM-001},</li>
 *   <li>condicion inactiva en la seleccion → 409 {@code QUO-007} (el detail nombra la condicion).</li>
 * </ul>
 * Lista {@code null}/vacia = sin condiciones (valido — el usuario puede desmarcar todas).
 *
 * <p>Lectura vs escritura (ADR-010): aca es ESCRITURA (exige todas activas). El render del
 * detalle/PDF resuelve por FK sin chequear {@code isActive} (muestra el snapshot historico).
 */
@ApplicationScoped
public class QuotationConditionPersistenceService {

    @Inject ConditionRepository conditionRepository;
    @Inject QuotationConditionRepository quotationConditionRepository;

    /**
     * Valida la seleccion contra el catalogo (unicidad, existencia, activas) SIN tocar la
     * cotizacion. Llamar ANTES de persistir el header (fail-fast). {@code null}/vacia = no-op.
     */
    public void validate(List<Integer> conditionIds) {
        if (conditionIds == null || conditionIds.isEmpty()) {
            return;
        }
        Set<Integer> unique = new HashSet<>(conditionIds);
        if (unique.size() != conditionIds.size()) {
            throw CommonError.VALIDATION_FAILED.toException(
                "La seleccion de condiciones tiene ids repetidos"
            );
        }
        Map<Integer, Condition> byId = conditionRepository.list("id in ?1", conditionIds)
            .stream().collect(Collectors.toMap(c -> c.id, Function.identity()));
        for (Integer id : conditionIds) {
            Condition condition = byId.get(id);
            if (condition == null) {
                throw CommonError.VALIDATION_FAILED.toException(
                    "La condicion con id " + id + " no existe"
                );
            }
            if (!Boolean.TRUE.equals(condition.isActive)) {
                throw QuotationsError.INACTIVE_CONDITION_SELECTED.toException(
                    "La condicion \"" + condition.text + "\" fue desactivada; quitala para guardar"
                );
            }
        }
    }

    /**
     * Inserta una fila en la junction por cada {@code conditionId}. Asume que {@link #validate}
     * ya paso (la cotizacion ya tiene id). {@code null}/vacia = no inserta nada.
     */
    public void persist(List<Integer> conditionIds, Long quotationId) {
        if (conditionIds == null || conditionIds.isEmpty()) {
            return;
        }
        for (Integer conditionId : conditionIds) {
            quotationConditionRepository.persist(new QuotationCondition(quotationId, conditionId));
        }
    }
}
