package com.scaramutti.tms.catalogs.quotationservicetype.mapper;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.catalogs.quotationservicetype.model.QuotationServiceKind;
import com.scaramutti.tms.shared.entity.QuotationServiceType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;

import java.util.List;

/**
 * Mapper de la capa Service: traduce entidades del dominio a DTOs de salida.
 *
 * El campo `kind` del response se COMPUTA desde el prefijo del `code`
 * (no es columna en BD). Se serializa como String (alineado con el patrón
 * del proyecto: ej. UserResponse.role tambien es String, no UserRole enum).
 *
 * `QuotationServiceKind.fromCode` se usa internamente para validar la
 * convencion y derivar el kind; el `.name()` final convierte el enum a String.
 *
 * El metodo de lista lo genera MapStruct automaticamente usando el de single.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface QuotationServiceTypeServiceMapper {

    @Mapping(target = "kind", source = "code", qualifiedByName = "codeToKindName")
    QuotationServiceTypeResponse toQuotationServiceTypeResponse(QuotationServiceType quotationServiceType);

    List<QuotationServiceTypeResponse> toQuotationServiceTypeResponseList(List<QuotationServiceType> quotationServiceTypes);

    @Named("codeToKindName")
    default String codeToKindName(String code) {
        return QuotationServiceKind.fromCode(code).name();
    }
}
