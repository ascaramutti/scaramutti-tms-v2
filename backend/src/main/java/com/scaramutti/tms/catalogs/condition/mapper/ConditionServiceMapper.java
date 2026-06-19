package com.scaramutti.tms.catalogs.condition.mapper;

import com.scaramutti.tms.catalogs.condition.dto.ConditionResponse;
import com.scaramutti.tms.shared.entity.Condition;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper de la capa Service: traduce entidades {@link Condition} a DTOs de salida.
 * El método de lista lo genera MapStruct usando el de single. Espeja {@code PaymentTermServiceMapper}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface ConditionServiceMapper {

    ConditionResponse toConditionResponse(Condition condition);

    List<ConditionResponse> toConditionResponseList(List<Condition> conditions);
}
