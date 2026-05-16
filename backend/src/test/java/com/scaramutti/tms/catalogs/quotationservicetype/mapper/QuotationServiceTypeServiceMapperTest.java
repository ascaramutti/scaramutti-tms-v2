package com.scaramutti.tms.catalogs.quotationservicetype.mapper;

import com.scaramutti.tms.catalogs.quotationservicetype.dto.QuotationServiceTypeResponse;
import com.scaramutti.tms.shared.entity.QuotationServiceType;
import com.scaramutti.tms.shared.exception.ApiException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests del mapper Entity → Response, en particular del campo `kind` computado
 * via @Mapping qualifiedByName. Cubre los happy path y los edge cases
 * defensivos para que un futuro refactor no rompa silenciosamente la cadena
 * code → kind.
 *
 * Nota: el response devuelve `kind` como String (alineado con el patron del
 * proyecto: UserResponse.role tambien es String). El mapper convierte el enum
 * `QuotationServiceKind` a String via `.name()`.
 */
@QuarkusTest
class QuotationServiceTypeServiceMapperTest {

    @Inject QuotationServiceTypeServiceMapper quotationServiceTypeServiceMapper;

    private QuotationServiceType buildEntity(String code, String name, String description, boolean isActive) {
        QuotationServiceType entity = new QuotationServiceType();
        entity.id = 1;
        entity.code = code;
        entity.name = name;
        entity.description = description;
        entity.isActive = isActive;
        return entity;
    }

    @Test
    void toQuotationServiceTypeResponse_withSPrefixCode_setsKindToServicio() {
        QuotationServiceType entity = buildEntity("SCB", "Servicio de transporte en Cama Baja", "Transporte", true);

        QuotationServiceTypeResponse response = quotationServiceTypeServiceMapper.toQuotationServiceTypeResponse(entity);

        assertEquals("SERVICIO", response.kind());
        assertEquals("SCB", response.code());
        assertEquals("Servicio de transporte en Cama Baja", response.name());
    }

    @Test
    void toQuotationServiceTypeResponse_withAPrefixCode_setsKindToAlquiler() {
        QuotationServiceType entity = buildEntity("ALM", "Almacenamiento", "Alquiler de espacio", true);

        QuotationServiceTypeResponse response = quotationServiceTypeServiceMapper.toQuotationServiceTypeResponse(entity);

        assertEquals("ALQUILER", response.kind());
    }

    @Test
    void toQuotationServiceTypeResponse_withCPrefixCode_setsKindToComplementario() {
        QuotationServiceType entity = buildEntity("CES", "Servicio de Escolta", "Escolta vehicular", true);

        QuotationServiceTypeResponse response = quotationServiceTypeServiceMapper.toQuotationServiceTypeResponse(entity);

        assertEquals("COMPLEMENTARIO", response.kind());
    }

    @Test
    void toQuotationServiceTypeResponse_withIPrefixCode_setsKindToIntegral() {
        QuotationServiceType entity = buildEntity("INT", "Servicio Integral", "Paquete", true);

        QuotationServiceTypeResponse response = quotationServiceTypeServiceMapper.toQuotationServiceTypeResponse(entity);

        assertEquals("INTEGRAL", response.kind());
    }

    @Test
    void toQuotationServiceTypeResponse_withNullCode_throwsCatalogsErrorCAT001() {
        // Defensa contra estado inconsistente: si una entity llegara con code=null
        // (bug de migración, corrupción de BD, etc.), el mapper debe fallar loudly
        // en vez de devolver kind=null. La columna es NOT NULL en BD, asi que en
        // produccion no deberia ocurrir, pero el test documenta el contrato.
        QuotationServiceType entity = buildEntity(null, "broken", null, true);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> quotationServiceTypeServiceMapper.toQuotationServiceTypeResponse(entity)
        );
        assertEquals("CAT-001", ex.code());
    }

    @Test
    void toQuotationServiceTypeResponse_preservesNullableDescription() {
        QuotationServiceType entity = buildEntity("SCB", "Servicio de transporte", null, true);

        QuotationServiceTypeResponse response = quotationServiceTypeServiceMapper.toQuotationServiceTypeResponse(entity);

        assertNotNull(response);
        assertNull(response.description());
    }

    @Test
    void toQuotationServiceTypeResponseList_mapsAllEntitiesAndComputesKindForEach() {
        List<QuotationServiceType> entities = List.of(
            buildEntity("SCB", "Transporte CB", null, true),
            buildEntity("ALM", "Almacenamiento", null, true),
            buildEntity("CES", "Escolta", null, true),
            buildEntity("INT", "Integral", null, true)
        );

        List<QuotationServiceTypeResponse> responses = quotationServiceTypeServiceMapper.toQuotationServiceTypeResponseList(entities);

        assertEquals(4, responses.size());
        assertEquals("SERVICIO",       responses.get(0).kind());
        assertEquals("ALQUILER",       responses.get(1).kind());
        assertEquals("COMPLEMENTARIO", responses.get(2).kind());
        assertEquals("INTEGRAL",       responses.get(3).kind());
    }
}
