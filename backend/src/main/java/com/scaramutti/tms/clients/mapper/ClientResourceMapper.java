package com.scaramutti.tms.clients.mapper;

import com.scaramutti.tms.clients.dto.ClientRequest;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.clients.service.cmd.ListClientsQuery;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST: traduce el request HTTP a un Command/Query del service.
 * Normaliza los strings antes de mandarlos al dominio:
 *  - name: trim + uppercase, "" → null (se almacena en mayúsculas en BD)
 *  - contactName: trim, "" → null (es nombre de persona, NO uppercase)
 *  - q (listing): trim + uppercase, "" → null. Se uppercasea aqui porque
 *    `name`/`ruc` se almacenan en mayusculas y el operador pg_trgm `%` es
 *    case-sensitive — sin upper el matching falla para inputs en minusculas.
 *    Reusa el mismo `trimUpperOrNull` que `name` para consistencia.
 *  - ruc, phone: pasan tal cual — Bean Validation (@Pattern) ya garantiza
 *    el formato exacto (sin espacios). Trim aqui seria codigo muerto.
 *
 * `uses = StringUtils.class` permite referenciar `StringUtils.trimToNull`
 * directamente vía `qualifiedByName = "trimToNull"`, sin wrappers locales.
 *
 * `nullValueMappingStrategy = RETURN_DEFAULT` (mapper-level, afecta TODOS los
 * metodos): cuando todos los source params nullable son null, MapStruct construye
 * el target con defaults en vez de devolver null.
 *  - `toListClientsQuery`: critico — caso `GET /clients` sin query-params manda
 *    q + isActive ambos null y es happy path valido.
 *  - `toCreateClientCommand`: cambia `null → null` por `null → Command(null,null,
 *    null,null)`. Inalcanzable en el flujo REST real (`@Valid` rechaza body null
 *    antes), y el guard `validatePostTrim` del service atrapa el name=null
 *    devolviendo COM-001 si pasase. Defense-in-depth intacta.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface ClientResourceMapper {

    @Mapping(target = "name",        source = "name",        qualifiedByName = "trimUpperOrNull")
    @Mapping(target = "contactName", source = "contactName", qualifiedByName = "trimToNull")
    CreateClientCommand toCreateClientCommand(ClientRequest clientRequest);

    @Mapping(target = "q", source = "q", qualifiedByName = "trimUpperOrNull")
    ListClientsQuery toListClientsQuery(String q, Boolean isActive, int page, int size);

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
