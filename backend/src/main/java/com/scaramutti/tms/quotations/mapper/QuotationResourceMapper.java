package com.scaramutti.tms.quotations.mapper;

import com.scaramutti.tms.quotations.dto.QuotationItemRequest;
import com.scaramutti.tms.quotations.dto.QuotationRequest;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostRequest;
import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;

import java.util.List;

/**
 * Mapper REST: traduce QuotationRequest (DTO) a CreateQuotationCommand (interno).
 *
 * Normalizacion aplicada:
 *  - contactName: trim, "" → null.
 *  - contactPhone: trim, "" → null (formato ya validado por Bean Validation @Pattern).
 *  - origin/destination: trim, "" → null.
 *  - observations (por item): trim, "" → null.
 *  - NO uppercase (origin/destination son lugares; observations es texto libre).
 *
 * Numericos pasan tal cual (BigDecimal). Las listas se mapean en orden.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI, uses = StringUtils.class)
public interface QuotationResourceMapper {

    @Mapping(target = "contactName",  source = "contactName",  qualifiedByName = "trimToNull")
    @Mapping(target = "contactPhone", source = "contactPhone", qualifiedByName = "trimToNull")
    @Mapping(target = "origin",       source = "origin",       qualifiedByName = "trimToNull")
    @Mapping(target = "destination",  source = "destination",  qualifiedByName = "trimToNull")
    CreateQuotationCommand toCreateQuotationCommand(QuotationRequest quotationRequest);

    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateQuotationCommand.Item toCommandItem(QuotationItemRequest itemRequest);

    List<CreateQuotationCommand.Item> toCommandItems(List<QuotationItemRequest> items);

    CreateQuotationCommand.Standby toCommandStandby(QuotationStandbyCostRequest request);
}
