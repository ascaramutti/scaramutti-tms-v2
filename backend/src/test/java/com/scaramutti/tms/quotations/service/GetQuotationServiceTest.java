package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.mapper.QuotationEmbeddedSummaryMapper;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationStandbyCostRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit del GetQuotationService. Mockea TODOS los colaboradores. Aisla:
 *  - 404 cuando el id no existe (QUO-003) + verifyNoInteractions con
 *    colaboradores.
 *  - {@code isExpired} derivado del estado (true SII status == EXPIRED, ADR-005).
 *  - Dedup de lookup de usuarios cuando createdBy == updatedBy (1 query).
 *  - Defensa contra items vacios (no NPE).
 *  - Orfandad de User FK → COM-500 (consistente con loader behavior).
 *
 * <p>Estilo: Mockito strict mode (default). Los stubs comunes viven en
 * {@link #setupHappyPathMocks()} y se invocan solo en los tests que los
 * necesitan, no en {@code @BeforeEach}. Esto deja que strict atrape stubs
 * muertos test-por-test (no globalmente).
 *
 * NO testea logica del loader/calculator/assembler (cubierta en sus propios
 * unit tests). Aqui solo verificamos la orquestacion y los flags pasados al
 * assembler.
 */
@ExtendWith(MockitoExtension.class)
class GetQuotationServiceTest {

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationItemRepository quotationItemRepository;
    @Mock QuotationStandbyCostRepository quotationStandbyCostRepository;
    @Mock ConditionRepository conditionRepository;
    @Mock UserRepository userRepository;
    @Mock QuotationDependencyLoaderService dependencyLoader;
    @Mock QuotationCalculatorService calculator;
    @Mock QuotationResponseAssemblerService assembler;
    @Mock QuotationEmbeddedSummaryMapper summaryMapper;
    @Mock AuthServiceMapper authServiceMapper;

    @InjectMocks GetQuotationService service;

    private Quotation quotation;
    private QuotationResponse expectedResponse;
    private UserResponse mockedUserResponse;

    @BeforeEach
    void initFixtures() {
        // Solo fixtures de data — sin stubs. Cada test que use los colaboradores
        // invoca setupHappyPathMocks() explicitamente.
        quotation = sampleQuotation(123L,
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),     // fresh
            15, 42, 42);
        expectedResponse = mockQuotationResponse();
        mockedUserResponse = new UserResponse(42, "admin", "Admin Sistema", "Admin", "admin", true);
    }

    /**
     * Mocks default del happy path. Los tests que solo testean fail-fast
     * paths (ej. 404) NO lo invocan.
     */
    private void setupHappyPathMocks() {
        when(quotationItemRepository.findByQuotationId(123L)).thenReturn(List.of());
        when(quotationStandbyCostRepository.findByQuotationId(123L)).thenReturn(List.of());
        when(dependencyLoader.loadByIds(any(), any(), any(), anySet(), anySet()))
            .thenReturn(emptyDependencies());
        when(calculator.calculateFromEntities(any()))
            .thenReturn(new QuotationCalculatorService.Totals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(userRepository.findById(42)).thenReturn(new User());
        // thenAnswer (no thenReturn): nueva instancia por llamada. Asi el test
        // de dedup puede verificar assertSame load-bearing — solo pasa si el
        // service NO re-invoco loadUser (dedup hit), no si Mockito devuelve
        // siempre el mismo objeto.
        when(authServiceMapper.toUserResponse(any())).thenAnswer(inv ->
            new UserResponse(42, "admin", "Admin Sistema", "Admin", "admin", true));
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(expectedResponse);
    }

    // ---------- Happy path -------------------------------------------------

    @Test
    void getById_existingId_returnsAssembledResponse() {
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        QuotationResponse result = service.getById(123L);

        assertNotNull(result);
        assertEquals(expectedResponse, result);
        verify(quotationRepository).findByIdOptional(123L);
        verify(quotationItemRepository).findByQuotationId(123L);
        verify(quotationStandbyCostRepository).findByQuotationId(123L);
        verify(dependencyLoader).loadByIds(any(), any(), any(), anySet(), anySet());
        verify(calculator).calculateFromEntities(any());
        verify(assembler).assemble(eq(quotation), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- 404 Not Found ----------------------------------------------

    @Test
    void getById_nonExistentId_throws_QUO003() {
        when(quotationRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.getById(999L));

        assertEquals("QUO-003", ex.code());
        assertEquals(404, ex.status());
        assertTrue(ex.getMessage().contains("999"),
            "El message (detail) debe incluir el id pedido — para que el frontend muestre directo");

        // No debe llamar a los colaboradores si el id no existe.
        verifyNoInteractions(quotationItemRepository, quotationStandbyCostRepository,
            dependencyLoader, calculator, assembler);
    }

    // ---------- isExpired derivado del estado (ADR-005) --------------------
    // isExpired = true SII status == EXPIRED. Ya NO se computa por fechas.

    @Test
    void getById_statusExpired_passesIsExpiredTrueToAssembler() {
        // EXPIRED es el UNICO estado con isExpired=true (lo deja el job).
        quotation.status = "EXPIRED";
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        service.getById(123L);

        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void getById_sentPastValidity_passesIsExpiredFalse_untilJobRuns() {
        // Cambio de semantica (ADR-005): una SENT vencida por FECHA sigue isExpired=false
        // hasta que el job la pase a EXPIRED. El read-path ya no mira createdAt+validityDays.
        quotation.status = "SENT";
        quotation.createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        quotation.validityDays = 15;
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        service.getById(123L);

        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    void getById_draftPastValidity_passesIsExpiredFalse() {
        quotation.status = "DRAFT";
        quotation.createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        quotation.validityDays = 15;
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        service.getById(123L);

        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    void getById_terminalAcceptedPastValidity_passesIsExpiredFalse() {
        // Terminal != EXPIRED → isExpired=false (no importa la fecha).
        quotation.status = "ACCEPTED";
        quotation.createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        quotation.validityDays = 15;
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        service.getById(123L);

        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    void getById_terminalRejected_passesIsExpiredFalse() {
        quotation.status = "REJECTED";
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        service.getById(123L);

        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), eq(false));
    }

    // ---------- Dedup createdBy/updatedBy ----------------------------------

    @Test
    void getById_sameCreatedByAndUpdatedBy_deduplicatesUserLookup() {
        // createdBy == updatedBy → solo 1 query al userRepository + el assembler
        // recibe el MISMO objeto UserResponse en ambos slots (referential equality).
        quotation.createdBy = 42;
        quotation.updatedBy = 42;
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();

        service.getById(123L);

        verify(userRepository, times(1)).findById(42);
        verify(userRepository, never()).findById(eq(43));

        // Verificar referential equality en el assembler — captura los args.
        ArgumentCaptor<UserResponse> createdByCaptor = ArgumentCaptor.forClass(UserResponse.class);
        ArgumentCaptor<UserResponse> updatedByCaptor = ArgumentCaptor.forClass(UserResponse.class);
        verify(assembler).assemble(
            any(), any(), any(), any(), any(), any(),
            createdByCaptor.capture(), updatedByCaptor.capture(), anyBoolean()
        );
        assertSame(createdByCaptor.getValue(), updatedByCaptor.getValue(),
            "Cuando createdBy==updatedBy, ambos args del assembler deben ser el MISMO objeto (dedup)");
    }

    @Test
    void getById_differentCreatedByAndUpdatedBy_loadsBothUsers() {
        // createdBy != updatedBy → 2 queries.
        quotation.createdBy = 42;
        quotation.updatedBy = 99;
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();
        when(userRepository.findById(99)).thenReturn(new User());

        service.getById(123L);

        verify(userRepository).findById(42);
        verify(userRepository).findById(99);
    }

    // ---------- Orfandad de User FK → COM-500 -------------------------------

    @Test
    void getById_createdByUserDeleted_throws_COM500() {
        // Edge: un admin borro el row de users que esta cotizacion referencia
        // (FK huerfana). El loader trata esto como bug de integridad — log +
        // INTERNAL_ERROR. Consistente con el manejo de orphan FK en
        // QuotationDependencyLoaderService.
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        when(quotationItemRepository.findByQuotationId(123L)).thenReturn(List.of());
        when(quotationStandbyCostRepository.findByQuotationId(123L)).thenReturn(List.of());
        when(dependencyLoader.loadByIds(any(), any(), any(), anySet(), anySet()))
            .thenReturn(emptyDependencies());
        when(calculator.calculateFromEntities(any()))
            .thenReturn(new QuotationCalculatorService.Totals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(userRepository.findById(42)).thenReturn(null);  // ← user no existe

        ApiException ex = assertThrows(ApiException.class, () -> service.getById(123L));

        assertEquals("COM-500", ex.code());
        assertEquals(500, ex.status());
        assertTrue(ex.getMessage().contains("42"),
            "El message debe incluir el userId huerfano para que soporte pueda investigar");
        // El assembler NO se llama si el user lookup falla.
        verifyNoInteractions(assembler);
    }

    // ---------- Defensive: items vacios -------------------------------------

    @Test
    void getById_emptyItems_doesNotThrowAndCallsAssemblerWithEmptyLists() {
        // Defense-in-depth: en teoria toda quotation tiene >=1 item (el validator
        // lo exige en CREATE) pero el READ no debe asumir invariantes del WRITE.
        when(quotationRepository.findByIdOptional(123L)).thenReturn(Optional.of(quotation));
        setupHappyPathMocks();
        // Nota: setupHappyPathMocks ya stubea findByQuotationId con List.of() —
        // este test confirma que no hay NPE en el flow.

        QuotationResponse result = service.getById(123L);

        assertNotNull(result);
        verify(assembler).assemble(any(), eq(List.of()), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- Helpers ----------------------------------------------------

    private Quotation sampleQuotation(Long id, OffsetDateTime createdAt, int validityDays,
                                      Integer createdBy, Integer updatedBy) {
        Quotation q = new Quotation();
        q.id = id;
        q.code = "2026-00042";
        q.quotationType = "TRANSPORTE";
        q.status = "DRAFT";
        q.clientId = 1;
        q.contactName = "Test Contact";
        q.currencyId = 1;
        q.paymentTermId = 1;
        q.validityDays = validityDays;
        q.origin = "Lima";
        q.destination = "Cusco";
        q.createdAt = createdAt;
        q.updatedAt = createdAt;
        q.createdBy = createdBy;
        q.updatedBy = updatedBy;
        return q;
    }

    private LoadedDependencies emptyDependencies() {
        return new LoadedDependencies(null, null, null, new HashMap<>(), new HashMap<>());
    }

    private QuotationResponse mockQuotationResponse() {
        // Shell — los tests no inspeccionan el contenido del response,
        // solo verifican que el assembler fue llamado con los args correctos.
        return new QuotationResponse(
            123L, "2026-00042",
            com.scaramutti.tms.quotations.model.QuotationType.TRANSPORTE,
            com.scaramutti.tms.quotations.model.QuotationStatus.DRAFT,
            null, "Test Contact", null, null, null, null,
            15, OffsetDateTime.now(), false, "Lima", "Cusco",
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), List.of(), null, null,
            OffsetDateTime.now(), OffsetDateTime.now()
        );
    }
}
