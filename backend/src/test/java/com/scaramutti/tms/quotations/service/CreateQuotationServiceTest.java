package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.quotations.QuotationsError;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationClientSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCurrencySummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.quotations.mapper.QuotationServiceMapper;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit del CreateQuotationService (orquestador puro). Mockea TODOS los
 * colaboradores incluido el QuotationResponseAssemblerService (que ahora
 * compone el response final, fuera del facade).
 *
 * Casos cubiertos:
 *  - Happy path: orquestacion correcta del flow (loader → validator →
 *    anti-dup → calcular → generar code → persistir → ensamblar response).
 *  - Loader tira COM-001 (por FK inexistente) → el orquestador propaga sin
 *    tocar el resto del flow.
 *  - Anti-duplicado (QUO-002) cuando existen cotizaciones recientes con
 *    mismos serviceTypeIds.
 *  - Anti-duplicado NO se dispara si los serviceTypeIds difieren.
 *  - Validator tira → aborta antes del anti-dup y persistencia.
 *
 * NO testea reglas de negocio (QuotationValidatorServiceTest), calculos
 * (QuotationCalculatorServiceTest), generacion de code
 * (QuotationCodeGeneratorServiceTest), carga de entidades
 * (QuotationDependencyLoaderServiceTest), ni composicion del response
 * (QuotationResponseAssemblerServiceTest — pendiente / integration tests).
 */
@ExtendWith(MockitoExtension.class)
class CreateQuotationServiceTest {

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationItemRepository quotationItemRepository;
    @Mock UserRepository userRepository;

    @Mock QuotationDependencyLoaderService dependencyLoader;
    @Mock QuotationCodeGeneratorService codeGenerator;
    @Mock QuotationValidatorService validator;
    @Mock QuotationCalculatorService calculator;
    @Mock QuotationItemPersistenceService itemPersistence;
    @Mock QuotationConditionPersistenceService conditionPersistence;
    @Mock QuotationResponseAssemblerService assembler;
    @Mock AuthServiceMapper authServiceMapper;
    @Mock QuotationServiceMapper quotationServiceMapper;

    @Mock CurrentUser currentUser;

    @InjectMocks CreateQuotationService service;

    @BeforeEach
    void initConfig() {
        // @ConfigProperty field no lo inyecta Mockito — lo seteamos manualmente. El
        // default-igv-percentage se movio a QuotationItemPersistenceService (mockeado aca).
        service.antiDuplicateWindowSeconds = 30;
    }

    private SaveQuotationCommand sampleCommand() {
        var items = List.of(new SaveQuotationCommand.Item(
            null, null, 1, null, null,
            null, null, null, null, 1, new BigDecimal("100"), null,
            null, null
        ));
        return new SaveQuotationCommand(
            QuotationType.TRANSPORTE, 1, "contact", null, 1, null, null, 15, "Lima", "Cusco", null, null, items, null
        );
    }

    private QuotationClientSummary sampleClient() {
        return new QuotationClientSummary(1, "ACME", "20100100100");
    }

    private QuotationCurrencySummary sampleCurrency() {
        return new QuotationCurrencySummary(1, "USD", "$");
    }

    private QuotationServiceTypeSummary sampleServiceType(int id, String code) {
        return new QuotationServiceTypeSummary(id, code, "test-" + code, "SERVICIO");
    }

    private UserResponse sampleUserResponse() {
        return new UserResponse(42, "admin", "Admin Sistema", "Admin", "admin", true);
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

    private Quotation sampleQuotation() {
        Quotation q = new Quotation();
        q.id = 100L;
        q.code = "2026-00001";
        q.createdAt = OffsetDateTime.now();
        q.updatedAt = OffsetDateTime.now();
        q.validityDays = 15;
        q.quotationType = "TRANSPORTE";
        q.status = "DRAFT";
        return q;
    }

    /**
     * Stub estandar del loader: devuelve una cotizacion valida con 1 serviceType
     * y sin cargoTypes.
     */
    private LoadedDependencies sampleDependencies() {
        return new LoadedDependencies(
            sampleClient(),
            sampleCurrency(),
            null,
            Map.of(1, sampleServiceType(1, "SCB")),
            Map.of()
        );
    }

    /**
     * Stub de la entity que el mapper devolveria. Construye una Quotation
     * minima con el code y user — los otros campos son don't-care para los
     * tests del orquestador (los detalles del mapeo los cubre el test del mapper
     * generado por MapStruct, que es trivial).
     */
    private Quotation stubMappedEntity(String code, Integer userId) {
        Quotation q = new Quotation();
        q.code = code;
        q.quotationType = "TRANSPORTE";
        q.status = "DRAFT";
        q.clientId = 1;
        q.currencyId = 1;
        q.validityDays = 15;
        q.createdBy = userId;
        q.updatedBy = userId;
        return q;
    }

    /**
     * Stub de assembler para devolver un response no-null. Como el orquestador
     * delega 100% el armado del response, basta con que retorne algo no-null
     * para verificar el wiring (los detalles los cubre el test del assembler).
     */
    private QuotationResponse stubAssembledResponse() {
        return new QuotationResponse(
            100L, "2026-00001", QuotationType.TRANSPORTE,
            com.scaramutti.tms.quotations.model.QuotationStatus.DRAFT,
            null, null, null, null, null, null, 15, null, false,
            null, null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), null, null, OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    // ---------- Happy path ---------------------------------------------------

    @Test
    void createQuotation_happyPath_persistsAndReturnsResponse() {
        var command = sampleCommand();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        when(quotationRepository.findRecentByCreatedByAndClient(eq(42), eq(1), anyInt()))
            .thenReturn(List.of());
        when(calculator.calculate(any())).thenReturn(new QuotationCalculatorService.Totals(
            new BigDecimal("100"), new BigDecimal("18"), new BigDecimal("118")
        ));
        when(codeGenerator.nextCode()).thenReturn("2026-00001");
        when(quotationServiceMapper.toQuotationEntity(any(), eq("2026-00001"), eq(42)))
            .thenReturn(stubMappedEntity("2026-00001", 42));
        when(itemPersistence.persistItems(any(), any())).thenReturn(List.of());
        when(itemPersistence.persistStandbyCosts(any(), any(), any())).thenReturn(Map.of());
        when(userRepository.findById(42)).thenReturn(sampleUser());
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(sampleUserResponse());
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(stubAssembledResponse());

        var response = service.createQuotation(command);

        assertNotNull(response);
        verify(dependencyLoader).loadFor(command);
        verify(validator).validate(eq(command), any());
        verify(codeGenerator).nextCode();
        verify(quotationRepository).persist(any(Quotation.class));
        verify(itemPersistence).persistItems(eq(command), any(Quotation.class));
        verify(conditionPersistence).validate(command.conditionIds());
        verify(conditionPersistence).persist(eq(command.conditionIds()), any());
        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- Loader propaga errores (early-fail) --------------------------

    @Test
    void createQuotation_whenLoaderThrowsCOM001_propagatesAndSkipsRest() {
        var command = sampleCommand();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command))
            .thenThrow(CommonError.VALIDATION_FAILED.toException("clientId no existe (id=999)"));

        ApiException ex = assertThrows(ApiException.class, () -> service.createQuotation(command));
        assertEquals("COM-001", ex.code());

        verify(validator, never()).validate(any(), any());
        verify(calculator, never()).calculate(any());
        verify(codeGenerator, never()).nextCode();
        verify(quotationRepository, never()).persist(any(Quotation.class));
        verify(assembler, never()).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- Anti-duplicado (QUO-002) ------------------------------------

    @Test
    void createQuotation_whenRecentDuplicateExists_throwsQUO002() {
        var command = sampleCommand();
        Quotation recent = sampleQuotation();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        when(quotationRepository.findRecentByCreatedByAndClient(eq(42), eq(1), anyInt()))
            .thenReturn(List.of(recent));
        when(quotationItemRepository.serviceTypeIdsForQuotations(List.of(100L)))
            .thenReturn(Set.of(1));

        ApiException ex = assertThrows(ApiException.class, () -> service.createQuotation(command));
        assertEquals("QUO-002", ex.code());
        assertEquals(QuotationsError.DUPLICATE_DETECTED.status(), ex.status());

        verify(quotationRepository, never()).persist(any(Quotation.class));
        verify(codeGenerator, never()).nextCode();
        verify(assembler, never()).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void createQuotation_whenRecentExistsButDifferentServiceTypes_doesNotTriggerDuplicate() {
        var command = sampleCommand();
        Quotation recent = sampleQuotation();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        when(quotationRepository.findRecentByCreatedByAndClient(eq(42), eq(1), anyInt()))
            .thenReturn(List.of(recent));
        when(quotationItemRepository.serviceTypeIdsForQuotations(List.of(100L)))
            .thenReturn(Set.of(999));
        when(calculator.calculate(any())).thenReturn(new QuotationCalculatorService.Totals(
            new BigDecimal("100"), new BigDecimal("18"), new BigDecimal("118")
        ));
        when(codeGenerator.nextCode()).thenReturn("2026-00002");
        when(quotationServiceMapper.toQuotationEntity(any(), eq("2026-00002"), eq(42)))
            .thenReturn(stubMappedEntity("2026-00002", 42));
        when(userRepository.findById(42)).thenReturn(sampleUser());
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(sampleUserResponse());
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(stubAssembledResponse());

        var response = service.createQuotation(command);

        assertNotNull(response);
        verify(quotationRepository).persist(any(Quotation.class));
        verify(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- Validator falla aborta flow ---------------------------------

    @Test
    void createQuotation_whenValidatorThrows_abortsBeforeAntiDuplicate() {
        var command = sampleCommand();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        doAnswer(inv -> {
            throw CommonError.VALIDATION_FAILED.toException("regla de negocio violada");
        }).when(validator).validate(any(), any());

        ApiException ex = assertThrows(ApiException.class, () -> service.createQuotation(command));
        assertEquals("COM-001", ex.code());

        verify(quotationRepository, never()).findRecentByCreatedByAndClient(anyInt(), anyInt(), anyInt());
        verify(codeGenerator, never()).nextCode();
        verify(quotationRepository, never()).persist(any(Quotation.class));
        verify(assembler, never()).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- Defense-in-depth: UNIQUE code violation → QUO-001 ----------

    /**
     * Si por race condition (advisory lock corrupto, replay attack o bug futuro)
     * dos requests generan el mismo code y el INSERT falla con UNIQUE violation,
     * el service traduce el ConstraintViolationException a QUO-001.
     */
    @Test
    void createQuotation_whenInsertFailsWithUniqueCodeViolation_throwsQUO001() {
        var command = sampleCommand();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        when(quotationRepository.findRecentByCreatedByAndClient(eq(42), eq(1), anyInt()))
            .thenReturn(List.of());
        when(calculator.calculate(any())).thenReturn(new QuotationCalculatorService.Totals(
            new BigDecimal("100"), new BigDecimal("18"), new BigDecimal("118")
        ));
        when(codeGenerator.nextCode()).thenReturn("2026-00001");
        when(quotationServiceMapper.toQuotationEntity(any(), eq("2026-00001"), eq(42)))
            .thenReturn(stubMappedEntity("2026-00001", 42));

        // Simula la violacion de UNIQUE constraint sobre la columna `code`.
        org.hibernate.exception.ConstraintViolationException cve =
            new org.hibernate.exception.ConstraintViolationException(
                "duplicate key value violates unique constraint",
                new java.sql.SQLException("duplicate key"),
                "quotations_code_unique");
        doAnswer(inv -> {
            throw new jakarta.persistence.PersistenceException("unique violation", cve);
        }).when(quotationRepository).flush();

        ApiException ex = assertThrows(ApiException.class, () -> service.createQuotation(command));
        assertEquals("QUO-001", ex.code());

        // El assembler NO debe haberse llamado (la persistencia fallo antes).
        verify(assembler, never()).assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    // ---------- Anti-duplicate lock orden ------------------------------------

    /**
     * Verifica que el advisory lock anti-duplicado se adquiera ANTES del check
     * de duplicados — sin esto, dos POST simultaneos podrian pasar el check
     * antes de que ninguno persista (ventana TOCTOU).
     */
    @Test
    void createQuotation_acquiresAntiDuplicateLockBeforeQueryingRecent() {
        var command = sampleCommand();

        when(currentUser.requireId()).thenReturn(42);
        when(dependencyLoader.loadFor(command)).thenReturn(sampleDependencies());
        when(quotationRepository.findRecentByCreatedByAndClient(eq(42), eq(1), anyInt()))
            .thenReturn(List.of());
        when(calculator.calculate(any())).thenReturn(new QuotationCalculatorService.Totals(
            new BigDecimal("100"), new BigDecimal("18"), new BigDecimal("118")
        ));
        when(codeGenerator.nextCode()).thenReturn("2026-00001");
        when(quotationServiceMapper.toQuotationEntity(any(), eq("2026-00001"), eq(42)))
            .thenReturn(stubMappedEntity("2026-00001", 42));
        when(userRepository.findById(42)).thenReturn(sampleUser());
        when(authServiceMapper.toUserResponse(any(User.class))).thenReturn(sampleUserResponse());
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(stubAssembledResponse());

        service.createQuotation(command);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(quotationRepository);
        inOrder.verify(quotationRepository).acquireAntiDuplicateLock(42, 1);
        inOrder.verify(quotationRepository).findRecentByCreatedByAndClient(eq(42), eq(1), anyInt());
    }
}
