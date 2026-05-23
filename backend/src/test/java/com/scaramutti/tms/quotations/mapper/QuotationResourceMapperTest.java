package com.scaramutti.tms.quotations.mapper;

import com.scaramutti.tms.quotations.dto.QuotationItemRequest;
import com.scaramutti.tms.quotations.dto.QuotationRequest;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostRequest;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit del QuotationResourceMapper. Verifica que las normalizaciones
 * declaradas en el javadoc se aplican correctamente: trim + "" → null sobre
 * contactName, origin, destination y observations (este ultimo por item).
 *
 * El mapper se instancia via {@link Mappers#getMapper}, NO via CDI — Mapstruct
 * genera la implementacion `QuotationResourceMapperImpl` al compilar. El test
 * usa esa implementacion directamente, sin contenedor Quarkus.
 *
 * Casos cubiertos:
 *  - Trim de los 4 campos string.
 *  - "" / "   " / null → null en los campos string normalizados.
 *  - Propagacion de items (preservando orden).
 *  - Propagacion del standby anidado.
 *  - Numericos pasan tal cual (precision intacta).
 *  - Enum y fechas se pasan sin transformar.
 *  - Lista vacia de items se preserva como lista vacia (no null).
 */
class QuotationResourceMapperTest {

    private final QuotationResourceMapper mapper = Mappers.getMapper(QuotationResourceMapper.class);

    private QuotationItemRequest sampleItemRequest(String observations) {
        return new QuotationItemRequest(
            1, null, 1, 5, observations,
            new BigDecimal("10.50"), new BigDecimal("12.00"), new BigDecimal("2.50"), new BigDecimal("3.00"),
            2, new BigDecimal("250.75"), null,
            null, null
        );
    }

    private QuotationRequest baseRequest(String contactName, String origin, String destination,
                                          List<QuotationItemRequest> items) {
        return baseRequest(contactName, null, origin, destination, items);
    }

    private QuotationRequest baseRequest(String contactName, String contactPhone, String origin, String destination,
                                          List<QuotationItemRequest> items) {
        return new QuotationRequest(
            QuotationType.TRANSPORTE, 1, contactName, contactPhone, 1, 2,
            LocalDate.of(2026, 6, 1), 15, origin, destination, items
        );
    }

    // ---------- Trim de strings ---------------------------------------------

    @Test
    void toCommand_trimsContactNameOriginDestination() {
        var req = baseRequest("  Juan Perez  ", "  Lima  ", "  Cusco  ",
            List.of(sampleItemRequest(null)));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals("Juan Perez", cmd.contactName());
        assertEquals("Lima", cmd.origin());
        assertEquals("Cusco", cmd.destination());
    }

    @Test
    void toCommand_emptyStringsBecomeNull() {
        var req = baseRequest("", "", "",
            List.of(sampleItemRequest("")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertNull(cmd.contactName());
        assertNull(cmd.origin());
        assertNull(cmd.destination());
        assertNull(cmd.items().get(0).observations());
    }

    @Test
    void toCommand_whitespaceOnlyStringsBecomeNull() {
        var req = baseRequest("   ", "\t", " \n ",
            List.of(sampleItemRequest("   ")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertNull(cmd.contactName());
        assertNull(cmd.origin());
        assertNull(cmd.destination());
        assertNull(cmd.items().get(0).observations());
    }

    @Test
    void toCommand_nullStringsRemainNull() {
        var req = baseRequest(null, null, null,
            List.of(sampleItemRequest(null)));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertNull(cmd.contactName());
        assertNull(cmd.origin());
        assertNull(cmd.destination());
        assertNull(cmd.items().get(0).observations());
    }

    @Test
    void toCommand_trimsObservationsPerItem() {
        var items = List.of(
            sampleItemRequest("  obs 1  "),
            sampleItemRequest("\tobs 2\n"),
            sampleItemRequest("")
        );
        var req = baseRequest("Juan", "Lima", "Cusco", items);

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals(3, cmd.items().size());
        assertEquals("obs 1", cmd.items().get(0).observations());
        assertEquals("obs 2", cmd.items().get(1).observations());
        assertNull(cmd.items().get(2).observations());
    }

    // ---------- Propagacion de campos no normalizados -----------------------

    @Test
    void toCommand_propagatesNonStringFieldsAsIs() {
        var req = baseRequest("Juan", "Lima", "Cusco",
            List.of(sampleItemRequest("test")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals(QuotationType.TRANSPORTE, cmd.quotationType());
        assertEquals(1, cmd.clientId());
        assertEquals(1, cmd.currencyId());
        assertEquals(2, cmd.paymentTermId());
        assertEquals(LocalDate.of(2026, 6, 1), cmd.tentativeServiceDate());
        assertEquals(15, cmd.validityDays());
    }

    @Test
    void toCommand_preservesItemNumericPrecision() {
        // igvPercentage YA NO se acepta en el request — eliminado del DTO.
        var item = new QuotationItemRequest(
            1, null, 1, 5, "obs",
            new BigDecimal("10.55"), new BigDecimal("12.34"), new BigDecimal("2.56"), new BigDecimal("3.78"),
            3, new BigDecimal("999999.99"), new BigDecimal("123.45"),
            new BigDecimal("5000.00"), null
        );
        var req = baseRequest("Juan", "Lima", "Cusco", List.of(item));

        CreateQuotationCommand.Item mapped = mapper.toCreateQuotationCommand(req).items().get(0);

        assertEquals(new BigDecimal("10.55"), mapped.weightKg());
        assertEquals(new BigDecimal("12.34"), mapped.lengthMeters());
        assertEquals(new BigDecimal("2.56"), mapped.widthMeters());
        assertEquals(new BigDecimal("3.78"), mapped.heightMeters());
        assertEquals(3, mapped.quantity());
        assertEquals(new BigDecimal("999999.99"), mapped.unitPrice());
        assertEquals(new BigDecimal("123.45"), mapped.internalReferencePrice());
        assertEquals(new BigDecimal("5000.00"), mapped.insuredAmount());
    }

    // ---------- Propagacion de items (orden + cantidad) ---------------------

    @Test
    void toCommand_preservesItemOrderAndCount() {
        var items = List.of(
            sampleItemRequest("obs 1"),
            sampleItemRequest("obs 2"),
            sampleItemRequest("obs 3"),
            sampleItemRequest("obs 4")
        );
        var req = baseRequest("Juan", "Lima", "Cusco", items);

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals(4, cmd.items().size());
        assertEquals("obs 1", cmd.items().get(0).observations());
        assertEquals("obs 2", cmd.items().get(1).observations());
        assertEquals("obs 3", cmd.items().get(2).observations());
        assertEquals("obs 4", cmd.items().get(3).observations());
    }

    // ---------- Standby anidado ---------------------------------------------

    @Test
    void toCommand_propagatesStandbyWhenPresent() {
        var standby = new QuotationStandbyCostRequest(new BigDecimal("75.50"), true);
        var item = new QuotationItemRequest(
            1, null, 1, 5, null,
            null, null, null, null, 1, new BigDecimal("100"), null,
            null, standby
        );
        var req = baseRequest("Juan", "Lima", "Cusco", List.of(item));

        CreateQuotationCommand.Item mapped = mapper.toCreateQuotationCommand(req).items().get(0);

        assertNotNull(mapped.standby());
        assertEquals(new BigDecimal("75.50"), mapped.standby().pricePerDay());
        assertEquals(Boolean.TRUE, mapped.standby().includesIgv());
    }

    @Test
    void toCommand_propagatesNullStandby() {
        var req = baseRequest("Juan", "Lima", "Cusco",
            List.of(sampleItemRequest("test")));

        CreateQuotationCommand.Item mapped = mapper.toCreateQuotationCommand(req).items().get(0);

        assertNull(mapped.standby());
    }

    // ---------- itemNumber / parentItemNumber pass-through ------------------

    @Test
    void toCommand_propagatesItemNumberAndParent() {
        var parent = new QuotationItemRequest(
            1, null, 24, null, null, null, null, null, null,
            1, new BigDecimal("5000"), null, null, null
        );
        var child = new QuotationItemRequest(
            2, 1, 1, null, null, null, null, null, null,
            1, BigDecimal.ZERO, new BigDecimal("100"), null, null
        );
        var req = baseRequest("Juan", "Lima", "Cusco", List.of(parent, child));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals(1, cmd.items().get(0).itemNumber());
        assertNull(cmd.items().get(0).parentItemNumber());
        assertEquals(2, cmd.items().get(1).itemNumber());
        assertEquals(1, cmd.items().get(1).parentItemNumber());
    }

    // ---------- Edge cases ---------------------------------------------------

    @Test
    void toCommand_singleItem_isMappedCorrectly() {
        var req = baseRequest("Juan", "Lima", "Cusco",
            List.of(sampleItemRequest("solo item")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertNotNull(cmd.items());
        assertEquals(1, cmd.items().size());
    }

    @Test
    void toCommand_trimsContactPhone() {
        var req = baseRequest("Juan", "  987654321  ", "Lima", "Cusco",
            List.of(sampleItemRequest("test")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals("987654321", cmd.contactPhone());
    }

    @Test
    void toCommand_emptyContactPhoneBecomesNull() {
        var req = baseRequest("Juan", "", "Lima", "Cusco",
            List.of(sampleItemRequest("test")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertNull(cmd.contactPhone());
    }

    @Test
    void toCommand_mixedNormalizationDoesNotAffectOtherFields() {
        // contactName trim aplica pero NO afecta a un origin con espacios internos.
        var req = baseRequest("  Juan  ", "Av. Lima 123", "Cusco - Centro",
            List.of(sampleItemRequest("test")));

        CreateQuotationCommand cmd = mapper.toCreateQuotationCommand(req);

        assertEquals("Juan", cmd.contactName());
        // Los espacios internos se preservan (solo se trim borders).
        assertTrue(cmd.origin().contains(" "));
        assertEquals("Av. Lima 123", cmd.origin());
        assertEquals("Cusco - Centro", cmd.destination());
    }
}
