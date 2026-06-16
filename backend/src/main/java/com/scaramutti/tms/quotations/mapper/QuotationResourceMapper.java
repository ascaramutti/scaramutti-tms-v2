package com.scaramutti.tms.quotations.mapper;

import com.scaramutti.tms.quotations.dto.QuotationItemRequest;
import com.scaramutti.tms.quotations.dto.QuotationRequest;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostRequest;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.quotations.service.cmd.ListQuotationsQuery;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueMappingStrategy;

import java.time.LocalDate;
import java.util.List;

/**
 * Mapper REST: traduce QuotationRequest (DTO) a SaveQuotationCommand (interno).
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
    @Mapping(target = "clientNote",   source = "clientNote",   qualifiedByName = "trimToNull")
    @Mapping(target = "internalNote", source = "internalNote", qualifiedByName = "trimToNull")
    SaveQuotationCommand toSaveQuotationCommand(QuotationRequest quotationRequest);

    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    SaveQuotationCommand.Item toCommandItem(QuotationItemRequest itemRequest);

    List<SaveQuotationCommand.Item> toCommandItems(List<QuotationItemRequest> items);

    SaveQuotationCommand.Standby toCommandStandby(QuotationStandbyCostRequest request);

    /**
     * Arma el query del listado desde los query-params del Resource. El `q` se
     * trim ("" → null); NO se uppercasea (el repo usa ILIKE case-insensitive).
     * El resto pasa tal cual (mapeo por nombre de parametro).
     *
     * <p>{@code @BeanMapping(nullValueMappingStrategy = RETURN_DEFAULT)} a nivel
     * de ESTE metodo (no del mapper): con todos los filtros null (caso
     * {@code GET /quotations} sin filtros — happy path), construye el query con
     * page/size en vez de devolver null. Aplicado solo aca para NO afectar
     * {@code toCommandStandby} (donde null→null es intencional).
     */
    @BeanMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
    @Mapping(target = "q", source = "q", qualifiedByName = "trimToNull")
    ListQuotationsQuery toListQuotationsQuery(
        String q,
        QuotationStatus status,
        QuotationType quotationType,
        Integer clientId,
        Integer createdById,
        Integer currencyId,
        Integer cargoTypeId,
        Integer serviceTypeId,
        LocalDate dateFrom,
        LocalDate dateTo,
        int page,
        int size
    );
}
