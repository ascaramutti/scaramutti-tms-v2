package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.cmd.ChangeQuotationStatusCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit del {@link ChangeQuotationStatusService} (orquestador del PATCH status). Mockea
 * colaboradores. Cubre el ORDEN estricto de checks (ADR-006): 404 → If-Match → transicion
 * → motivo; la regla del motivo (obligatorio Y exclusivo de REJECTED, ADR-007); que NO
 * persiste si algun check falla; y que el response se reusa de {@code getById} post-flush.
 *
 * <p>El {@code statusMachine} esta mockeado a proposito (este test verifica la orquestacion,
 * no la matriz de transiciones — esa la cubre {@link QuotationStatusMachineTest}). Para los
 * happy paths se stubea {@code canTransition(...)=true}; para el caso de transicion invalida
 * se stubea {@code false}.
 */
@ExtendWith(MockitoExtension.class)
class ChangeQuotationStatusServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-06-01T10:00:30Z");

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationStatusMachine statusMachine;
    @Mock GetQuotationService getQuotationService;
    @Mock CurrentUser currentUser;

    @InjectMocks ChangeQuotationStatusService service;

    // ---------- Fixtures -----------------------------------------------------

    private String etagOf(OffsetDateTime t) {
        return "\"" + t.toString() + "\"";
    }

    /** Cotizacion managed con el status dado, updatedAt=T0. */
    private Quotation existingWith(String status) {
        Quotation q = new Quotation();
        q.id = 100L;
        q.code = "2026-00001";
        q.quotationType = "TRANSPORTE";
        q.status = status;
        q.clientId = 1;
        q.createdBy = 10;
        q.updatedBy = 10;
        q.createdAt = T0.minusDays(1);
        q.updatedAt = T0;
        q.validityDays = 15;
        return q;
    }

    private ChangeQuotationStatusCommand cmd(QuotationStatus to, String reason) {
        return new ChangeQuotationStatusCommand(to, reason);
    }

    private QuotationResponse stubResponse() {
        return new QuotationResponse(
            100L, "2026-00001", QuotationType.TRANSPORTE, QuotationStatus.SENT,
            null, null, null, null, null, null, 15, null, false,
            null, null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), null, null, OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    // ---------- Happy paths --------------------------------------------------

    @Test
    void changeStatus_draftToSent_persistsAndReusesGetById() {
        var existing = existingWith("DRAFT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.DRAFT, QuotationStatus.SENT)).thenReturn(true);
        var expected = stubResponse();
        when(getQuotationService.getById(100L)).thenReturn(expected);

        var response = service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.SENT, null));

        assertSame(expected, response, "el response se reusa de getById post-flush");
        assertEquals("SENT", existing.status);
        assertEquals(42, existing.updatedBy);     // updatedBy = usuario actual
        org.junit.jupiter.api.Assertions.assertNull(existing.rejectionReason);
        verify(quotationRepository).flush();
        verify(getQuotationService).getById(100L);
    }

    @Test
    void changeStatus_sentToRejected_withReason_persistsTrimmedReason() {
        var existing = existingWith("SENT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.SENT, QuotationStatus.REJECTED)).thenReturn(true);
        when(getQuotationService.getById(100L)).thenReturn(stubResponse());

        service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.REJECTED, "  precio alto  "));

        assertEquals("REJECTED", existing.status);
        assertEquals("precio alto", existing.rejectionReason);   // trimmeado
        verify(quotationRepository).flush();
    }

    @Test
    void changeStatus_sentToAccepted_persistsAndNullsReason() {
        var existing = existingWith("SENT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.SENT, QuotationStatus.ACCEPTED)).thenReturn(true);
        when(getQuotationService.getById(100L)).thenReturn(stubResponse());

        service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.ACCEPTED, null));

        assertEquals("ACCEPTED", existing.status);
        org.junit.jupiter.api.Assertions.assertNull(existing.rejectionReason);
    }

    // ---------- 404 (precede a todo) -----------------------------------------

    @Test
    void changeStatus_notFound_throwsQUO003_andSkipsEverything() {
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(999L, etagOf(T0), cmd(QuotationStatus.SENT, null)));

        assertEquals("QUO-003", ex.code());
        assertEquals(404, ex.status());
        verify(quotationRepository, never()).flush();
        verify(getQuotationService, never()).getById(any());
    }

    // ---------- If-Match (precede a la transicion) ---------------------------

    @Test
    void changeStatus_staleIfMatch_throwsCOM004_beforeTransitionCheck() {
        var existing = existingWith("DRAFT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0.minusHours(1)), cmd(QuotationStatus.SENT, null)));

        assertEquals("COM-004", ex.code());
        assertEquals(412, ex.status());
        // El If-Match precede a la maquina de estados.
        verify(statusMachine, never()).canTransition(any(), any());
        verify(quotationRepository, never()).flush();
    }

    @Test
    void changeStatus_nullIfMatch_throwsCOM004() {
        var existing = existingWith("DRAFT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, null, cmd(QuotationStatus.SENT, null)));

        assertEquals("COM-004", ex.code());
        verify(quotationRepository, never()).flush();
    }

    // ---------- Transicion invalida (QUO-005) --------------------------------

    @Test
    void changeStatus_invalidTransition_throwsQUO005_withFromToInDetail() {
        var existing = existingWith("DRAFT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.DRAFT, QuotationStatus.ACCEPTED)).thenReturn(false);

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.ACCEPTED, null)));

        assertEquals("QUO-005", ex.code());
        assertEquals(409, ex.status());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("DRAFT")
            && ex.getMessage().contains("ACCEPTED"),
            "el detail debe nombrar origen y destino: " + ex.getMessage());
        verify(quotationRepository, never()).flush();
    }

    @Test
    void changeStatus_fromTerminal_throwsQUO005() {
        // Salir de un terminal: la maquina lo rechaza (canTransition=false).
        var existing = existingWith("ACCEPTED");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.ACCEPTED, QuotationStatus.SENT)).thenReturn(false);

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.SENT, null)));

        assertEquals("QUO-005", ex.code());
        verify(quotationRepository, never()).flush();
    }

    // ---------- Motivo de rechazo (ADR-007): obligatorio Y exclusivo ----------

    @Test
    void changeStatus_rejectedWithoutReason_throwsCOM001() {
        var existing = existingWith("SENT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.SENT, QuotationStatus.REJECTED)).thenReturn(true);

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.REJECTED, null)));

        assertEquals("COM-001", ex.code());
        assertEquals(400, ex.status());
        verify(quotationRepository, never()).flush();
    }

    @Test
    void changeStatus_rejectedWithBlankReason_throwsCOM001() {
        var existing = existingWith("SENT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.SENT, QuotationStatus.REJECTED)).thenReturn(true);

        // Blank tras trim (el mapper real lo dejaria null, pero aca defendemos el service directo).
        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.REJECTED, "   ")));

        assertEquals("COM-001", ex.code());
        verify(quotationRepository, never()).flush();
    }

    @Test
    void changeStatus_acceptedWithReason_throwsCOM001_reasonIsExclusiveToRejected() {
        var existing = existingWith("SENT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.SENT, QuotationStatus.ACCEPTED)).thenReturn(true);

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.ACCEPTED, "no deberia ir")));

        assertEquals("COM-001", ex.code());
        verify(quotationRepository, never()).flush();
    }

    @Test
    void changeStatus_sentWithReason_throwsCOM001() {
        var existing = existingWith("DRAFT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));
        when(statusMachine.canTransition(QuotationStatus.DRAFT, QuotationStatus.SENT)).thenReturn(true);

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.SENT, "motivo huerfano")));

        assertEquals("COM-001", ex.code());
        verify(quotationRepository, never()).flush();
    }

    // ---------- Destinos no-usuario (DRAFT/EXPIRED) → QUO-005 -----------------

    @Test
    void changeStatus_userTargetExpired_throwsQUO005_evenIfMachineWouldAllow() {
        // SENT → EXPIRED es valido en la maquina (transicion del job) pero NO es destino de
        // usuario: el PATCH lo rechaza con QUO-005 antes de consultar la maquina.
        var existing = existingWith("SENT");
        when(currentUser.requireId()).thenReturn(42);
        when(quotationRepository.findByIdOptional(100L)).thenReturn(Optional.of(existing));

        var ex = assertThrows(ApiException.class,
            () -> service.changeStatus(100L, etagOf(T0), cmd(QuotationStatus.EXPIRED, null)));

        assertEquals("QUO-005", ex.code());
        // No consulta la maquina (el destino no-usuario corta antes, short-circuit del OR).
        verify(statusMachine, never()).canTransition(any(), any());
        verify(quotationRepository, never()).flush();
    }
}
