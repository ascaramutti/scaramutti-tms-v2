package com.scaramutti.tms.clients.mapper;

import com.scaramutti.tms.clients.dto.ClientRequest;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;

/**
 * Mapper de la capa REST: traduce el request HTTP a un Command del service.
 * Normaliza los strings antes de mandarlos al dominio:
 *  - name: trim + uppercase, "" → null (se almacena en mayúsculas en BD)
 *  - contactName: trim, "" → null (es nombre de persona, NO uppercase)
 *  - ruc, phone: pasan tal cual — Bean Validation (@Pattern) ya garantiza
 *    el formato exacto (sin espacios). Trim aqui seria codigo muerto.
 *
 * `uses = StringUtils.class` permite referenciar `StringUtils.trimToNull`
 * directamente vía `qualifiedByName = "trimToNull"`, sin wrappers locales.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI, uses = StringUtils.class)
public interface ClientResourceMapper {

    @Mapping(target = "name",        source = "name",        qualifiedByName = "trimUpperOrNull")
    @Mapping(target = "contactName", source = "contactName", qualifiedByName = "trimToNull")
    CreateClientCommand toCreateClientCommand(ClientRequest clientRequest);

    /**
     * Trim + uppercase. Delega normalización vacío → null a `StringUtils.trimToNull`.
     * Usado para `name` (razón social): se almacena en mayúsculas en BD.
     */
    @Named("trimUpperOrNull")
    default String trimUpperOrNull(String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase();
    }
}
