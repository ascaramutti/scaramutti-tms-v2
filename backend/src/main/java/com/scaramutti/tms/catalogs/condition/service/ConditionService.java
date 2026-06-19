package com.scaramutti.tms.catalogs.condition.service;

import com.scaramutti.tms.catalogs.condition.dto.ConditionResponse;
import com.scaramutti.tms.catalogs.condition.mapper.ConditionServiceMapper;
import com.scaramutti.tms.catalogs.condition.service.cmd.ListConditionsQuery;
import com.scaramutti.tms.shared.entity.Condition;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Lista el catálogo de condiciones generales. {@code isActive=null} → todas; true/false →
 * filtra. Siempre ordenadas por {@code display_order} ASC (RN-04). Espeja {@code PaymentTermService}.
 */
@ApplicationScoped
public class ConditionService {

    @Inject ConditionRepository conditionRepository;
    @Inject ConditionServiceMapper conditionServiceMapper;

    public List<ConditionResponse> listConditions(ListConditionsQuery listConditionsQuery) {
        Boolean isActiveFilter = listConditionsQuery.isActive();
        List<Condition> conditions = (isActiveFilter == null)
            ? conditionRepository.listAllOrderedByDisplayOrder()
            : conditionRepository.listByIsActiveOrderedByDisplayOrder(isActiveFilter);

        return conditionServiceMapper.toConditionResponseList(conditions);
    }
}
