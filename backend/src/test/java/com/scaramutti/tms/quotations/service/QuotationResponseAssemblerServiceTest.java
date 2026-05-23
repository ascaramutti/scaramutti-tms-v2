package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.quotations.dto.QuotationItemResponse;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCargoTypeSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationPaymentTermSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit del QuotationResponseAssemblerService.
 *
 * El assembler es deterministico (no llama a now() ni a mappers) — el test
 * lo instancia directo sin Mockito.
 *
 * Cubre:
 *  - Mapeo 1:1 de campos del header (id, code, status, fechas, totales).
 *  - Subtotal=0 para hijos del Integral; subtotal=qty*unitPrice para root.
 *  - Agrupacion jerarquica: hijos van bajo `children` del padre.
 *  - expiresAt = createdAt + validityDays (computado en assembler).
 *  - isExpired pasado por parametro (deterministic, el caller decide).
 *  - Nullable handling: paymentTerm null, cargoType null, standby ausente.
 *  - Items huerfanos: si un child tiene parentItemId que no resuelve a root,
 *    se loguea warning y NO se incluye en el response.
 *  - createdBy y updatedBy independientes (preparado para UPDATE futuro).
 *  - Cero items: response valido con items=[].
 */
class QuotationResponseAssemblerServiceTest {

    private final QuotationResponseAssemblerService assembler = new QuotationResponseAssemblerService();

    private Quotation quotation;
    private QuotationClientSummary client;
    private QuotationCurrencySummary currency;
    private UserResponse createdBy;
    private UserResponse updatedBy;

    @BeforeEach
    void initSamples() {
        quotation = new Quotation();
        quotation.id = 100L;
        quotation.code = "2026-00042";
        quotation.quotationType = "TRANSPORTE";
        quotation.status = "DRAFT";
        quotation.contactName = "Juan Perez";
        quotation.tentativeServiceDate = null;
        quotation.validityDays = 15;
        quotation.origin = "Lima";
        quotation.destination = "Cusco";
        // Timestamps fijos — el assembler es deterministic, el test no debe depender de now().
        quotation.createdAt = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        quotation.updatedAt = OffsetDateTime.parse("2026-01-01T10:00:00Z");

        client = new QuotationClientSummary(1, "ACME", "20100100100");
        currency = new QuotationCurrencySummary(1, "USD", "$");
        createdBy = new UserResponse(42, "admin", "Admin Sistema", "Admin", "admin", true);
        updatedBy = createdBy;  // En CREATE coinciden; tests de UPDATE pueden diferenciar.
    }

    private QuotationItem rootItem(long id, int number, Integer serviceTypeId, BigDecimal unitPrice, int qty) {
        QuotationItem qi = new QuotationItem();
        qi.id = id; qi.quotationId = 100L; qi.parentItemId = null;
        qi.itemNumber = number;
        qi.quotationServiceTypeId = serviceTypeId; qi.cargoTypeId = null;
        qi.quantity = qty; qi.unitPrice = unitPrice;
        qi.igvPercentage = new BigDecimal("18.00");
        return qi;
    }

    private QuotationItem childItem(long id, long parentId, int number, Integer serviceTypeId) {
        QuotationItem qi = new QuotationItem();
        qi.id = id; qi.quotationId = 100L; qi.parentItemId = parentId;
        qi.itemNumber = number;
        qi.quotationServiceTypeId = serviceTypeId;
        qi.quantity = 1; qi.unitPrice = BigDecimal.ZERO;
        qi.igvPercentage = new BigDecimal("18.00");
        return qi;
    }

    private QuotationServiceTypeSummary st(int id, String code) {
        return new QuotationServiceTypeSummary(id, code, "test-" + code, "SERVICIO");
    }

    private LoadedDependencies depsWith(Map<Integer, QuotationServiceTypeSummary> types,
                                        Map<Integer, QuotationCargoTypeSummary> cargos,
                                        QuotationPaymentTermSummary pt) {
        return new LoadedDependencies(client, currency, pt, types, cargos);
    }

    private QuotationCalculatorService.Totals totals(String sub, String igv, String total) {
        return new QuotationCalculatorService.Totals(
            new BigDecimal(sub), new BigDecimal(igv), new BigDecimal(total)
        );
    }

    // ---------- Mapeo basico del header --------------------------------------

    @Test
    void assemble_mapsHeaderFields() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNotNull(resp);
        assertEquals(100L, resp.id());
        assertEquals("2026-00042", resp.code());
        assertEquals("Lima", resp.origin());
        assertEquals("Cusco", resp.destination());
        assertEquals(new BigDecimal("100"), resp.totalSubtotal());
        assertEquals(new BigDecimal("18"), resp.totalIgv());
        assertEquals(new BigDecimal("118"), resp.totalAmount());
        assertEquals(client, resp.client());
        assertEquals(currency, resp.currency());
        assertEquals(createdBy, resp.createdBy());
        assertEquals(updatedBy, resp.updatedBy());
        assertFalse(resp.isExpired());
    }

    // ---------- expiresAt computado por el assembler -------------------------

    @Test
    void assemble_computesExpiresAtFromCreatedAtPlusValidity() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertEquals(quotation.createdAt.plusDays(15), resp.expiresAt());
    }

    @Test
    void assemble_isExpiredFromParam_isPassedThrough() {
        // El assembler NO computa isExpired — lo recibe del caller. Verificamos
        // ambos valores se propagan sin tocar.
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse respFalse = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );
        QuotationResponse respTrue = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, true
        );

        assertFalse(respFalse.isExpired());
        assertTrue(respTrue.isExpired());
    }

    // ---------- Subtotal por item --------------------------------------------

    @Test
    void assemble_computesSubtotalForRootItems() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("250.00"), 3);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("750", "135", "885"), deps,
            createdBy, updatedBy, false
        );

        assertEquals(1, resp.items().size());
        assertEquals(new BigDecimal("750.00"), resp.items().get(0).subtotal());
    }

    @Test
    void assemble_setsZeroSubtotalForChildItems() {
        QuotationItem parent = rootItem(1L, 1, 99, new BigDecimal("100"), 1);
        QuotationItem child1 = childItem(2L, 1L, 2, 1);
        QuotationItem child2 = childItem(3L, 1L, 3, 10);
        var deps = depsWith(Map.of(
            99, st(99, "INT"), 1, st(1, "SCB"), 10, st(10, "CGRUA")
        ), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(parent, child1, child2), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertEquals(1, resp.items().size());
        QuotationItemResponse root = resp.items().get(0);
        assertEquals(new BigDecimal("100"), root.subtotal());
        assertNotNull(root.children());
        assertEquals(2, root.children().size());
        assertEquals(BigDecimal.ZERO, root.children().get(0).subtotal());
        assertEquals(BigDecimal.ZERO, root.children().get(1).subtotal());
    }

    // ---------- Agrupacion jerarquica ----------------------------------------

    @Test
    void assemble_groupsChildrenUnderParent() {
        QuotationItem parent = rootItem(10L, 1, 99, new BigDecimal("500"), 1);
        QuotationItem child = childItem(11L, 10L, 2, 1);
        var deps = depsWith(Map.of(99, st(99, "INT"), 1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(parent, child), Map.of(), totals("500", "90", "590"), deps,
            createdBy, updatedBy, false
        );

        assertEquals(1, resp.items().size());
        QuotationItemResponse root = resp.items().get(0);
        assertEquals(10L, root.id());
        assertEquals(1, root.children().size());
        assertEquals(11L, root.children().get(0).id());
        assertEquals(10L, root.children().get(0).parentItemId());
    }

    @Test
    void assemble_rootWithoutChildren_hasNullChildren() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNull(resp.items().get(0).children());
    }

    // ---------- Items huerfanos (defensive) ---------------------------------

    @Test
    void assemble_childWithoutMatchingParent_throwsIllegalStateException() {
        // Caso patologico: un child con parentItemId que NO existe entre los root.
        // Es invariante violada — fail-fast (era warning silencioso antes, lo
        // cambiamos a throw para evitar persistir cotizacion con datos inconsistentes
        // que el response esconderia).
        QuotationItem root = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        QuotationItem orphan = childItem(2L, 999L, 2, 1);  // parentItemId=999 — no existe
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> assembler.assemble(
                quotation, List.of(root, orphan), Map.of(), totals("100", "18", "118"), deps,
                createdBy, updatedBy, false
            )
        );
        assertTrue(ex.getMessage().contains("Orphan"));
        assertTrue(ex.getMessage().contains("999"));
        assertTrue(ex.getMessage().contains("rolled back"));
    }

    // ---------- Nullable handling --------------------------------------------

    @Test
    void assemble_paymentTermNull_propagatesNull() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNull(resp.paymentTerm());
    }

    @Test
    void assemble_paymentTermPresent_propagated() {
        QuotationPaymentTermSummary pt = new QuotationPaymentTermSummary(1, "Contado", 0);
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), pt);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNotNull(resp.paymentTerm());
        assertEquals("Contado", resp.paymentTerm().name());
    }

    @Test
    void assemble_cargoTypeNullOnItem_propagatesNull() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        item.cargoTypeId = null;
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNull(resp.items().get(0).cargoType());
    }

    @Test
    void assemble_cargoTypeOnItem_propagated() {
        QuotationCargoTypeSummary ct = new QuotationCargoTypeSummary(5, "Maquinaria pesada");
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        item.cargoTypeId = 5;
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(5, ct), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNotNull(resp.items().get(0).cargoType());
        assertEquals(5, resp.items().get(0).cargoType().id());
    }

    // ---------- createdBy / updatedBy independientes ------------------------

    @Test
    void assemble_createdByAndUpdatedBy_canDiffer() {
        // Preparado para UPDATE futuro donde el updater no es el creator.
        UserResponse otherUser = new UserResponse(99, "supervisor", "Otro User", "Supervisor", "admin", true);
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, otherUser, false
        );

        assertEquals(42, resp.createdBy().id());
        assertEquals(99, resp.updatedBy().id());
    }

    // ---------- Lista vacia --------------------------------------------------

    @Test
    void assemble_emptyItems_returnsEmptyList() {
        var deps = depsWith(Map.of(), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, new ArrayList<>(), new HashMap<>(), totals("0", "0", "0"), deps,
            createdBy, updatedBy, false
        );

        assertNotNull(resp.items());
        assertTrue(resp.items().isEmpty());
    }

    // ---------- Standby ya-Response (sin mapper) ----------------------------

    @Test
    void assemble_standbyOnItem_propagated() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        QuotationStandbyCostResponse standby = new QuotationStandbyCostResponse(
            1L, new BigDecimal("50"), false
        );
        Map<Long, QuotationStandbyCostResponse> standbyMap = Map.of(1L, standby);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), standbyMap, totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNotNull(resp.items().get(0).standby());
        assertEquals(new BigDecimal("50"), resp.items().get(0).standby().pricePerDay());
    }

    @Test
    void assemble_standbyAbsent_propagatesNull() {
        QuotationItem item = rootItem(1L, 1, 1, new BigDecimal("100"), 1);
        var deps = depsWith(Map.of(1, st(1, "SCB")), Map.of(), null);

        QuotationResponse resp = assembler.assemble(
            quotation, List.of(item), Map.of(), totals("100", "18", "118"), deps,
            createdBy, updatedBy, false
        );

        assertNull(resp.items().get(0).standby());
    }
}
