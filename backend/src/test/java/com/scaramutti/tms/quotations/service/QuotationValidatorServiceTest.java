package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.catalogs.quotationservicetype.model.QuotationServiceKind;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit del validator. Sin mocks externos — el service recibe el mapa de
 * serviceTypes ya precargado por el caller. Esto lo hace 100% testeable
 * con datos en memoria.
 *
 * Cubre cada una de las reglas declaradas en el javadoc del service:
 *  - origin/destination conditional por quotationType
 *  - max 5 items root
 *  - unitPrice required en root, debe ser 0/null en hijos
 *  - parentItemNumber referencia valida
 *  - insuredAmount solo en SEG
 *  - Servicio Integral: solo 1 INT, debe ser itemNumber=1, >=2 hijos,
 *    >=1 TRANSPORTE + >=1 COMPLEMENTARIO
 *  - itemNumber: contiguidad cuando es explicito
 */
class QuotationValidatorServiceTest {

    private final QuotationValidatorService validator = new QuotationValidatorService();

    // --------------- Helpers ----------------------------------------------

    /**
     * Construye un Response con el kind derivado del prefijo del code (mismo
     * patron que el mapper de produccion). Asi los tests no tienen que
     * pasar el kind a mano.
     */
    private QuotationServiceTypeSummary serviceType(int id, String code) {
        String kind = QuotationServiceKind.fromCode(code).name();
        return new QuotationServiceTypeSummary(id, code, "test-" + code, kind);
    }

    private Map<Integer, QuotationServiceTypeSummary> mapOf(QuotationServiceTypeSummary... types) {
        Map<Integer, QuotationServiceTypeSummary> m = new HashMap<>();
        for (QuotationServiceTypeSummary t : types) m.put(t.id(), t);
        return m;
    }

    /**
     * Helper para items kind != SERVICIO (ALQUILER, COMPLEMENTARIO, INTEGRAL).
     * SIN weight/cargoType — la regla del proyecto exige que esos campos sean
     * null en kinds que no son transporte (rechazado por validateMeasurementsAndCargoType).
     */
    private SaveQuotationCommand.Item item(
            Integer itemNumber, Integer parentItemNumber, Integer serviceTypeId,
            BigDecimal unitPrice, BigDecimal internalRef, BigDecimal insured) {
        return new SaveQuotationCommand.Item(
            itemNumber, parentItemNumber, serviceTypeId, null, null,
            null, null, null, null, 1, unitPrice, internalRef,
            insured, null
        );
    }

    /**
     * Helper para items kind=SERVICIO (transporte). Incluye weightKg=10
     * y cargoTypeId=1 por default — obligatorios para items de transporte.
     */
    private SaveQuotationCommand.Item transportItem(
            Integer itemNumber, Integer parentItemNumber, Integer serviceTypeId,
            BigDecimal unitPrice, BigDecimal internalRef, BigDecimal insured) {
        return new SaveQuotationCommand.Item(
            itemNumber, parentItemNumber, serviceTypeId, 1, null,
            new BigDecimal("10.00"), null, null, null, 1, unitPrice, internalRef,
            insured, null
        );
    }

    /**
     * Helper para tests de standby con item kind != SERVICIO.
     * Sin weight/cargoType.
     */
    private SaveQuotationCommand.Item itemWithStandby(
            Integer itemNumber, Integer parentItemNumber, Integer serviceTypeId,
            BigDecimal unitPrice) {
        var standby = new SaveQuotationCommand.Standby(new BigDecimal("50.00"), false);
        return new SaveQuotationCommand.Item(
            itemNumber, parentItemNumber, serviceTypeId, null, null,
            null, null, null, null, 1, unitPrice, null,
            null, standby
        );
    }

    /**
     * Helper para tests de standby con item kind=SERVICIO (transporte).
     * Incluye weight + cargoType obligatorios.
     */
    private SaveQuotationCommand.Item transportItemWithStandby(
            Integer itemNumber, Integer parentItemNumber, Integer serviceTypeId,
            BigDecimal unitPrice) {
        var standby = new SaveQuotationCommand.Standby(new BigDecimal("50.00"), false);
        return new SaveQuotationCommand.Item(
            itemNumber, parentItemNumber, serviceTypeId, 1, null,
            new BigDecimal("10.00"), null, null, null, 1, unitPrice, null,
            null, standby
        );
    }

    private SaveQuotationCommand command(QuotationType type, String origin, String destination,
                                            List<SaveQuotationCommand.Item> items) {
        return new SaveQuotationCommand(
            type, 1, "contact", null, 1, 1, null, 15, origin, destination, null, null, items
        );
    }

    // --------------- origin/destination conditional -----------------------

    @Test
    void validate_transporteWithoutOrigin_throwsCOM001() {
        var items = List.of(item(null, null, 1, new BigDecimal("100"), null, null));
        var cmd = command(QuotationType.TRANSPORTE, null, "Dest", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_transporteWithoutDestination_throwsCOM001() {
        var items = List.of(item(null, null, 1, new BigDecimal("100"), null, null));
        var cmd = command(QuotationType.TRANSPORTE, "Orig", null, items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_alquilerWithoutOriginDestination_isValid() {
        var items = List.of(item(null, null, 1, new BigDecimal("100"), null, null));
        var cmd = command(QuotationType.ALQUILER, null, null, items);

        assertDoesNotThrow(() -> validator.validate(cmd, mapOf(serviceType(1, "ACB"))));
    }

    // --------------- max 5 items root --------------------------------------

    @Test
    void validate_with6RootItems_throwsCOM001() {
        var items = List.of(
            item(null, null, 1, new BigDecimal("100"), null, null),
            item(null, null, 1, new BigDecimal("100"), null, null),
            item(null, null, 1, new BigDecimal("100"), null, null),
            item(null, null, 1, new BigDecimal("100"), null, null),
            item(null, null, 1, new BigDecimal("100"), null, null),
            item(null, null, 1, new BigDecimal("100"), null, null)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_with1IntegralRootPlus5NonIntegralRoots_throwsCOM001() {
        // El item Integral cuenta como root. 1 INT + 5 root no-Integral = 6 root → falla.
        // Documenta explicitamente la regla "el Integral es root".
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),  // INT root
            item(2, 1,     1, BigDecimal.ZERO, null, null),         // hijo (no cuenta como root)
            item(3, 1,    18, BigDecimal.ZERO, null, null),         // hijo (no cuenta como root)
            item(4, null,  1, new BigDecimal("100"), null, null),   // root 2
            item(5, null,  1, new BigDecimal("100"), null, null),   // root 3
            item(6, null,  1, new BigDecimal("100"), null, null),   // root 4
            item(7, null,  1, new BigDecimal("100"), null, null),   // root 5
            item(8, null,  1, new BigDecimal("100"), null, null)    // root 6 → excede max
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);
        var types = mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(18, "CES"));

        ApiException ex = assertThrows(ApiException.class, () -> validator.validate(cmd, types));
        assertEquals("COM-001", ex.code());
        // Mensaje debe mencionar el max y que el Integral cuenta como root.
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().contains("5") && ex.getMessage().toLowerCase().contains("integral"),
            "Mensaje debe explicitar que el Integral cuenta como root: " + ex.getMessage()
        );
    }

    @Test
    void validate_with5RootItems_isValid() {
        // 5 items SCB (kind=SERVICIO) → cada uno requiere weight + cargoType.
        var items = List.of(
            transportItem(null, null, 1, new BigDecimal("100"), null, null),
            transportItem(null, null, 1, new BigDecimal("100"), null, null),
            transportItem(null, null, 1, new BigDecimal("100"), null, null),
            transportItem(null, null, 1, new BigDecimal("100"), null, null),
            transportItem(null, null, 1, new BigDecimal("100"), null, null)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        assertDoesNotThrow(() -> validator.validate(cmd, mapOf(serviceType(1, "SCB"))));
    }

    // --------------- unitPrice required en root, 0 en hijos ---------------

    @Test
    void validate_rootItemWithoutUnitPrice_throwsCOM001() {
        var items = List.of(item(null, null, 1, null, null, null));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_rootItemWithUnitPriceZero_throwsCOM001() {
        // Root con unitPrice=0 ahora es invalido (solo hijos del Integral pueden ir sin precio).
        var items = List.of(transportItem(null, null, 1, BigDecimal.ZERO, null, null));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().contains("mayor a 0"),
            "Mensaje debe indicar la regla: " + ex.getMessage()
        );
    }

    @Test
    void validate_rootItemWithNegativeUnitPrice_throwsCOM001() {
        // Defense-in-depth: Bean Validation @DecimalMin(0) deberia atrapar negativos,
        // pero el validator tambien los rechaza explicitamente con > 0.
        var items = List.of(transportItem(null, null, 1, new BigDecimal("-50"), null, null));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_childItemWithUnitPriceNonZero_throwsCOM001() {
        // Hijo del Integral con unitPrice != 0 → invalido.
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),  // INT root
            item(2, 1,    1, new BigDecimal("100"), null, null),    // hijo con price
            item(3, 1,   18, new BigDecimal("0"), null, null)       // hijo complementario
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);
        var types = mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(18, "CES"));

        ApiException ex = assertThrows(ApiException.class, () -> validator.validate(cmd, types));
        assertEquals("COM-001", ex.code());
    }

    // --------------- internalReferencePrice solo en hijos -----------------

    @Test
    void validate_rootItemWithInternalReferencePrice_throwsCOM001() {
        var items = List.of(item(null, null, 1, new BigDecimal("100"), new BigDecimal("50"), null));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    // --------------- parent reference valida ------------------------------

    @Test
    void validate_parentItemNumberReferencesNonExistent_throwsCOM001() {
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),  // INT
            item(2, 99,    1, BigDecimal.ZERO, null, null)           // parent=99 NO existe
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(24, "INT"), serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    // --------------- insuredAmount solo en SEG ----------------------------

    @Test
    void validate_insuredAmountOnNonSegService_throwsCOM001() {
        // Item SCB (no SEG) con insuredAmount → invalido.
        var items = List.of(item(null, null, 1, new BigDecimal("100"), null, new BigDecimal("10000")));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    // --------------- Servicio Integral ------------------------------------

    @Test
    void validate_integralAsNonFirstItem_throwsCOM001() {
        // INT como itemNumber=2 (debe ser 1) → invalido.
        var items = List.of(
            transportItem(1, null,  1, new BigDecimal("100"), null, null),   // SCB transport
            item(2, null, 24, new BigDecimal("5000"), null, null),           // INT NOT first
            transportItem(3, 2,     1, BigDecimal.ZERO, null, null),         // SCB child transport
            item(4, 2,    18, BigDecimal.ZERO, null, null)                   // CES no-transport
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);
        var types = mapOf(serviceType(1, "SCB"), serviceType(24, "INT"), serviceType(18, "CES"));

        ApiException ex = assertThrows(ApiException.class, () -> validator.validate(cmd, types));
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_integralWithOnlyOneChild_throwsCOM001() {
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),
            transportItem(2, 1, 1, BigDecimal.ZERO, null, null)  // solo 1 hijo SCB (transport)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(24, "INT"), serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_integralWithoutTransporteChild_throwsCOM001() {
        // 2 hijos pero ambos COMPLEMENTARIO → sin TRANSPORTE.
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),
            item(2, 1,    18, BigDecimal.ZERO, null, null),   // CES
            item(3, 1,    18, BigDecimal.ZERO, null, null)    // CES
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(24, "INT"), serviceType(18, "CES")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_integralWithoutComplementarioChild_throwsCOM001() {
        // 2 hijos pero ambos SERVICIO (kind=S/TRANSPORTE) → sin COMPLEMENTARIO.
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),
            transportItem(2, 1, 1, BigDecimal.ZERO, null, null),   // SCB (S) transport
            transportItem(3, 1, 3, BigDecimal.ZERO, null, null)    // SPL (S) transport
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(3, "SPL")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_integralWithCorrectMix_isValid() {
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),         // INT root (no transport)
            transportItem(2, 1, 1, BigDecimal.ZERO, null, null),           // SCB child (transport, requiere weight+cargo)
            item(3, 1, 18, BigDecimal.ZERO, null, null)                    // CES child (complementario, no transport)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        assertDoesNotThrow(() -> validator.validate(
            cmd, mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(18, "CES"))
        ));
    }

    @Test
    void validate_twoIntegralsInQuotation_throwsCOM001() {
        // Solo puede haber 1 INT por cotizacion.
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),
            item(2, null, 24, new BigDecimal("3000"), null, null)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(24, "INT")))
        );
        assertEquals("COM-001", ex.code());
    }

    // --------------- itemNumber contiguidad cuando es explicito -----------

    @Test
    void validate_itemNumbersNonContiguous_throwsCOM001() {
        var items = List.of(
            item(1, null, 1, new BigDecimal("100"), null, null),
            item(3, null, 1, new BigDecimal("100"), null, null)  // gap: salta del 1 al 3
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_itemNumberMixedNullAndExplicit_throwsCOM001() {
        var items = List.of(
            item(1, null, 1, new BigDecimal("100"), null, null),
            item(null, null, 1, new BigDecimal("100"), null, null)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(
            ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB")))
        );
        assertEquals("COM-001", ex.code());
    }

    // --------------- Medidas + cargoType: solo en kind=SERVICIO -----------

    @Test
    void validate_servicioItemWithoutWeight_throwsCOM001() {
        // SCB (SERVICIO) sin weightKg → debe rechazar.
        var items = List.of(item(null, null, 1, new BigDecimal("100"), null, null));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB"))));
        assertEquals("COM-001", ex.code());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("weightKg"));
    }

    @Test
    void validate_servicioItemWithoutCargoTypeId_throwsCOM001() {
        // SCB con weight pero sin cargoTypeId → debe rechazar.
        // Construyo el item inline porque transportItem ya pone cargoTypeId=1.
        var item = new SaveQuotationCommand.Item(
            null, null, 1, null, null,  // cargoTypeId=null intentional
            new BigDecimal("10.00"), null, null, null, 1, new BigDecimal("100"), null,
            null, null
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", List.of(item));

        ApiException ex = assertThrows(ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB"))));
        assertEquals("COM-001", ex.code());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("cargoTypeId"));
    }

    @Test
    void validate_servicioItemWithWeightZero_throwsCOM001() {
        // weight=0 no es valido para transporte (debe ser >0).
        var item = new SaveQuotationCommand.Item(
            null, null, 1, 1, null,
            BigDecimal.ZERO, null, null, null, 1, new BigDecimal("100"), null,
            null, null
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", List.of(item));

        ApiException ex = assertThrows(ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(1, "SCB"))));
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_alquilerItemWithWeight_throwsCOM001() {
        // ALQUILER no debe tener weight.
        var item = new SaveQuotationCommand.Item(
            null, null, 9, null, null,
            new BigDecimal("10.00"), null, null, null, 1, new BigDecimal("500"), null,
            null, null
        );
        var cmd = command(QuotationType.ALQUILER, null, null, List.of(item));

        ApiException ex = assertThrows(ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(9, "ACB"))));
        assertEquals("COM-001", ex.code());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("SERVICIO"));
    }

    @Test
    void validate_complementarioItemWithCargoType_throwsCOM001() {
        // COMPLEMENTARIO no debe tener cargoTypeId.
        var item = new SaveQuotationCommand.Item(
            null, null, 18, 1, null,  // cargoTypeId=1 indebido
            null, null, null, null, 1, new BigDecimal("100"), null,
            null, null
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", List.of(item));

        ApiException ex = assertThrows(ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(18, "CES"))));
        assertEquals("COM-001", ex.code());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("cargoTypeId"));
    }

    @Test
    void validate_integralItemWithMeasurements_throwsCOM001() {
        // El item Integral (padre) no debe tener weight/dimensiones.
        var integralPadre = new SaveQuotationCommand.Item(
            1, null, 24, null, null,
            new BigDecimal("5.00"), null, null, null, 1, new BigDecimal("5000"), null,  // weight indebido
            null, null
        );
        var items = List.of(integralPadre,
            transportItem(2, 1, 1, BigDecimal.ZERO, null, null),
            item(3, 1, 18, BigDecimal.ZERO, null, null)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        ApiException ex = assertThrows(ApiException.class,
            () -> validator.validate(cmd, mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(18, "CES"))));
        assertEquals("COM-001", ex.code());
    }

    @Test
    void validate_servicioItemWithAllMeasurements_isValid() {
        // SERVICIO con weight + cargoType + length/width/height: OK.
        var item = new SaveQuotationCommand.Item(
            null, null, 1, 1, null,
            new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("2.50"), new BigDecimal("3.00"),
            1, new BigDecimal("100"), null,
            null, null
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", List.of(item));

        assertDoesNotThrow(() -> validator.validate(cmd, mapOf(serviceType(1, "SCB"))));
    }

    // --------------- Standby: NO aplica a INTEGRAL ------------------------

    @Test
    void validate_integralItemWithStandby_throwsCOM001() {
        // Item root con kind=INTEGRAL y standby asociado → invalido.
        // El Integral es un agregado del precio; sus standby viven en hijos.
        var items = List.of(
            itemWithStandby(1, null, 24, new BigDecimal("5000")),  // INT + standby
            item(2, 1, 1, BigDecimal.ZERO, null, null),
            item(3, 1, 18, BigDecimal.ZERO, null, null)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);
        var types = mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(18, "CES"));

        ApiException ex = assertThrows(ApiException.class, () -> validator.validate(cmd, types));
        assertEquals("COM-001", ex.code());
        org.junit.jupiter.api.Assertions.assertTrue(
            ex.getMessage().toLowerCase().contains("standby")
                && ex.getMessage().toLowerCase().contains("integral"),
            "Mensaje debe mencionar standby y Servicio Integral: " + ex.getMessage()
        );
    }

    @Test
    void validate_servicioItemWithStandby_isValid() {
        // kind=SERVICIO (prefijo S, e.g. SCB) puede tener standby + requiere weight + cargoType.
        var items = List.of(transportItemWithStandby(null, null, 1, new BigDecimal("100")));
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);

        assertDoesNotThrow(() -> validator.validate(cmd, mapOf(serviceType(1, "SCB"))));
    }

    @Test
    void validate_alquilerItemWithStandby_isValid() {
        // kind=ALQUILER (prefijo A) puede tener standby.
        var items = List.of(itemWithStandby(null, null, 9, new BigDecimal("500")));
        var cmd = command(QuotationType.ALQUILER, null, null, items);

        assertDoesNotThrow(() -> validator.validate(cmd, mapOf(serviceType(9, "ACB"))));
    }

    @Test
    void validate_complementarioItemAsChildWithStandby_isValid() {
        // kind=COMPLEMENTARIO (prefijo C) puede tener standby.
        // Como child del Integral en este caso — confirma que la regla aplica al
        // kind del item, no a su rol (root vs child).
        var items = List.of(
            item(1, null, 24, new BigDecimal("5000"), null, null),          // INT root (no transport)
            transportItem(2, 1, 1, BigDecimal.ZERO, null, null),            // SCB child (transport, requiere weight+cargo)
            itemWithStandby(3, 1, 18, BigDecimal.ZERO)                      // CES child con standby (no transport)
        );
        var cmd = command(QuotationType.TRANSPORTE, "O", "D", items);
        var types = mapOf(serviceType(24, "INT"), serviceType(1, "SCB"), serviceType(18, "CES"));

        assertDoesNotThrow(() -> validator.validate(cmd, types));
    }
}
