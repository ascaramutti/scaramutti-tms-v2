package com.scaramutti.tms.quotations.mapper;

import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * Mapper de la capa Service del modulo Quotations.
 * Convierte {@link CreateQuotationCommand} (+ {@code code} generado + {@code userId}
 * del auth context) en una {@link Quotation} entity lista para persistir.
 *
 * <p>Patron alineado con {@code ClientServiceMapper.toClientEntity} y
 * {@code CargoTypeServiceMapper.toCargoTypeEntity}. Consistencia cross-modulo.
 *
 * <p>Campos especiales:
 * <ul>
 *   <li>{@code code}: input del segundo argumento — generado por
 *       {@code QuotationCodeGeneratorService} antes de invocar al mapper.</li>
 *   <li>{@code userId}: input del tercer argumento — auth context resuelto
 *       por {@code CurrentUser.requireId()}. Se asigna a {@code createdBy} y
 *       {@code updatedBy} (en CREATE coinciden).</li>
 *   <li>{@code status}: constant "DRAFT" — toda cotizacion nueva nace como borrador.</li>
 *   <li>{@code quotationType}: enum del command → name() string para la columna BD.</li>
 *   <li>{@code id}, {@code createdAt}, {@code updatedAt}: ignored — la BD asigna
 *       el id por IDENTITY y los timestamps los setea el callback {@code @PrePersist}.</li>
 * </ul>
 *
 * <p>MapStruct genera la impl al compilar ({@code QuotationServiceMapperImpl}).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface QuotationServiceMapper {

    @Mapping(target = "id",                   ignore = true)
    @Mapping(target = "createdAt",            ignore = true)
    @Mapping(target = "updatedAt",            ignore = true)
    @Mapping(target = "code",                 source = "code")
    @Mapping(target = "quotationType",        expression = "java(command.quotationType().name())")
    @Mapping(target = "status",               constant = "DRAFT")
    @Mapping(target = "clientId",             source = "command.clientId")
    @Mapping(target = "contactName",          source = "command.contactName")
    @Mapping(target = "contactPhone",         source = "command.contactPhone")
    @Mapping(target = "currencyId",           source = "command.currencyId")
    @Mapping(target = "paymentTermId",        source = "command.paymentTermId")
    @Mapping(target = "tentativeServiceDate", source = "command.tentativeServiceDate")
    @Mapping(target = "validityDays",         source = "command.validityDays")
    @Mapping(target = "origin",               source = "command.origin")
    @Mapping(target = "destination",          source = "command.destination")
    @Mapping(target = "createdBy",            source = "userId")
    @Mapping(target = "updatedBy",            source = "userId")
    Quotation toQuotationEntity(CreateQuotationCommand command, String code, Integer userId);
}
