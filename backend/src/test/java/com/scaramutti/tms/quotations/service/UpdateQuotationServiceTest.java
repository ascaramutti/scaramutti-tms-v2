package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.quotations.mapper.QuotationServiceMapper;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationStandbyCostRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit del UpdateQuotationService (orquestador del PUT). Mockea todos los colaboradores.
 * Cubre: happy path (orquestacion + replace de items), optimistic locking (If-Match
 * stale/null → 412 COM-004), 404, inmutables (quotationType/clientId → 400 QUO-004),
 * early-fail (validator/loader), orden delete→insert, y que NO corre el anti-duplicado.
 *
 * <p>La PRESERVACION efectiva de los campos inmutables (createdBy/code/createdAt) la
 * cubre el integration test con el mapper real + BD; aca el mapper esta mockeado, asi
 * que solo se verifica que el service invoca {@code applyUpdate} con el usuario actual.
 */
@ExtendWith(MockitoExtension.class)
class UpdateQuotationServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-06-01T10:00:30Z");

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationItemRepository quotationItemRepository;
    @Mock QuotationStandbyCostRepository quotationStandbyCostRepository;
    @Mock UserRepository userRepository;

    @Mock QuotationDependencyLoaderService dependencyLoader;
    @Mock QuotationValidatorService validator;
    @Mock QuotationCalculatorService calculator;
    @Mock QuotationItemPersistenceService itemPersistence;
    @Mock QuotationResponseAssemblerService assembler;
    @Mock AuthServiceMapper authServiceMapper;
    @Mock QuotationServiceMapper quotationServiceMapper;

    @Mock CurrentUser currentUser;

    @InjectMocks UpdateQuotationService service;

    // ---------- Fixtures -----------------------------------------------------

    private String etagOf(OffsetDateTime t) {
        return "\"" + t.toString() + "\"";
    }

    private SaveQuotationCommand sampleCommand(QuotationType type, Integer clientId) {
        var items = List.of(new SaveQuotationCommand.Item(
            null, null, 1, null, null,
            null, null, null, null, 1, new BigDecimal("100"), null,
            null, null
        ));
        return new SaveQuotationCommand(
            type, clientId, "ZTEST_contact", null, 1, null, null, 15, "Lima", "Cusco", items
        );
    }

    private SaveQuotationCommand sampleCommand() {
        return sampleCommand(QuotationType.TRANSPORTE, 1);
    }

    /** Cotizacion persistida (managed): TRANSPORTE, clientId=1, createdBy=10, updatedAt=T0. */
    private Quotation sampleExisting() {
        Quotation q = new Quotation();
        q.id = 100L;
        q.code = "2026-00001";
        q.quotationType = "TRANSPORTE";
        q.status = "DRAFT";
        q.clientId = 1;
        q.createdBy = 10;
        q.updatedBy = 10;
        q.createdAt = T0.minusDays(1);
        q.updatedAt = T0;
        q.validityDays = 15;
        return q;
    }

    private LoadedDependencies sampleDependencies() {
        return new LoadedDependencies(
            new QuotationClientSummary(1, "ACME", "20100100100"),
            new QuotationCurrencySummary(1, "USD", "$"),
            null,
            Map.of(1, new QuotationServiceTypeSummary(1, "SCB", "test", "SERVICIO")),
            Map.of()
        );
    }

    private QuotationCalculatorService.Totals sampleTotals() {
        return new QuotationCalculatorService.Totals(
            new BigDecimal("100"), new BigDecimal("18"), new BigDecimal("118")
        );
    }

    private User sampleUser() {
        User u = new User();
        u.id = 42;
        u.username = "admin";
        Worker w = new Worker();
        w.id = 1; w.firstName = "Admin"; w.lastName = "Sistema"; w.position = "Admin";
        u.worker = w;
        return u;
    }

    private UserResponse sampleUserResponse() {
        return new UserResponse(42, "admin", "Admin Sistema", "Admin", "admin", true);
    }

    private QuotationResponse stubResponse() {
        return new QuotationResponse(
            100L, "2026-00001", QuotationType.TRANSPORTE, QuotationStatus.DRAFT,
            null, null, null, null, null, null, 15, null, false,
            null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), null, null, OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    /** Stubs comunes del flujo happy (desde despues del If-Match hasta el assemble). */
    private void stubHappyFlow(Quotation existing, SaveQuotationCommand command) {
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        when(calculator.calculate(any())).thenReturn(sampleTotals());
        when(itemPersistence.persistItems(any(), any())).thenReturn(List.of());
        when(itemPersistence.persistStandbyCosts(any(), any(), any())).thenReturn(Map.of());
        when(userRepository.findById(anyInt())).thenReturn(sampleUser());
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(sampleUserResponse());
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(stubResponse());
    }

    // ---------- Happy path ---------------------------------------------------

    @Test
    void update_happyPath_loadsValidatesReplacesItemsAndReturnsResponse() {
        var existing = sampleExisting();
        var command = sampleCommand();
        stubHappyFlow(existing, command);

        var response = service.updateQuotation(100L, etagOf(T0), command);

        assertNotNull(response);
        verify(validator).validate(eq(command), any());
        verify(quotationServiceMapper).applyUpdate(eq(existing), eq(command), eq(42));
        verify(itemPersistence).persistItems(eq(command), eq(existing));
        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void update_replacesItems_deletesStandbyThenItemsBeforeInserting() {
        var existing = sampleExisting();
        var command = sampleCommand();
        stubHappyFlow(existing, command);

        service.updateQuotation(100L, etagOf(T0), command);

        // Orden critico: standby → items → (flush) → persist. Por la FK + el UNIQUE.
        InOrder ordered = inOrder(quotationStandbyCostRepository, quotationItemRepository, itemPersistence);
        ordered.verify(quotationStandbyCostRepository).deleteByQuotationId(100L);
        ordered.verify(quotationItemRepository).deleteByQuotationId(100L);
        ordered.verify(itemPersistence).persistItems(any(), any());
    }

    @Test
    void update_doesNotRunAntiDuplicateCheck() {
        var existing = sampleExisting();
        var command = sampleCommand();
        stubHappyFlow(existing, command);

        service.updateQuotation(100L, etagOf(T0), command);

        // El anti-duplicado es exclusivo del CREATE — editar no debe dispararlo.
        verify(quotationRepository, never()).acquireAntiDuplicateLock(anyInt(), anyInt());
        verify(quotationRepository, never()).findRecentByCreatedByAndClient(anyInt(), anyInt(), anyInt());
    }

    // ---------- Optimistic locking (If-Match) --------------------------------

    @Test
    void update_staleIfMatch_throwsCOM004_andDoesNotPersist() {
        var existing = sampleExisting();   // updatedAt = T0
        var command = sampleCommand();
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        // If-Match con una version vieja (otro usuario edito primero → updatedAt avanzo).
        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(100L, etagOf(T0.minusHours(1)), command));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
        verify(validator, never()).validate(any(), any());
        verify(itemPersistence, never()).persistItems(any(), any());
    }

    @Test
    void update_nullIfMatch_throwsCOM004() {
        var existing = sampleExisting();
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(100L, null, sampleCommand()));

        assertEquals("COM-004", ex.code());
        verify(itemPersistence, never()).persistItems(any(), any());
    }

    // ---------- 404 ----------------------------------------------------------

    @Test
    void update_notFound_throwsQUO003_andSkipsEverything() {
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(999L, etagOf(T0), sampleCommand()));

        assertEquals("QUO-003", ex.code());
        assertEquals(404, ex.status());
        verify(validator, never()).validate(any(), any());
        verify(itemPersistence, never()).persistItems(any(), any());
    }

    // ---------- Inmutables (QUO-004) -----------------------------------------

    @Test
    void update_changingQuotationType_throwsQUO004() {
        var existing = sampleExisting();   // TRANSPORTE
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        var command = sampleCommand(QuotationType.ALQUILER, 1);   // intenta cambiar el tipo
        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(100L, etagOf(T0), command));

        assertEquals("QUO-004", ex.code());
        assertEquals(400, ex.status());
        verify(validator, never()).validate(any(), any());
        verify(itemPersistence, never()).persistItems(any(), any());
    }

    @Test
    void update_changingClientId_throwsQUO004() {
        var existing = sampleExisting();   // clientId = 1
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        var command = sampleCommand(QuotationType.TRANSPORTE, 2);   // intenta cambiar el cliente
        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(100L, etagOf(T0), command));

        assertEquals("QUO-004", ex.code());
        verify(itemPersistence, never()).persistItems(any(), any());
    }

    // ---------- updatedBy = usuario actual -----------------------------------

    @Test
    void update_callsApplyUpdateWithCurrentUser() {
        var existing = sampleExisting();   // createdBy = 10
        var command = sampleCommand();
        stubHappyFlow(existing, command);

        service.updateQuotation(100L, etagOf(T0), command);

        // El mapper recibe el usuario actual (42) para updatedBy; el createdBy (10) lo
        // preserva el mapper real (aca mockeado). La preservacion efectiva se valida en integration.
        verify(quotationServiceMapper).applyUpdate(eq(existing), eq(command), eq(42));
    }

    // ---------- Early-fail (loader / validator) ------------------------------

    @Test
    void update_validatorThrows_abortsBeforePersistence() {
        var existing = sampleExisting();
        var command = sampleCommand();
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        doThrow(com.scaramutti.tms.shared.exception.CommonError.VALIDATION_FAILED.toException())
            .when(validator).validate(eq(command), any());

        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(100L, etagOf(T0), command));

        assertEquals("COM-001", ex.code());
        verify(itemPersistence, never()).persistItems(any(), any());
        verify(quotationStandbyCostRepository, never()).deleteByQuotationId(any());
    }

    @Test
    void update_loaderThrows_propagatesAndSkipsValidation() {
        var existing = sampleExisting();
        var command = sampleCommand();
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(dependencyLoader.loadFor(command))
            .thenThrow(com.scaramutti.tms.shared.exception.CommonError.VALIDATION_FAILED.toException());

        var ex = assertThrows(ApiException.class,
            () -> service.updateQuotation(100L, etagOf(T0), command));

        assertEquals("COM-001", ex.code());
        verify(validator, never()).validate(any(), any());
        verify(itemPersistence, never()).persistItems(any(), any());
    }
}
