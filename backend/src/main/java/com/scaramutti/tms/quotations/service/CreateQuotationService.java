package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.auth.security.CurrentUser;
import com.scaramutti.tms.quotations.QuotationsError;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.mapper.QuotationServiceMapper;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.QuotationStandbyCost;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationStandbyCostRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facade del flow de creacion de cotizacion. Orquesta entre:
 *  - QuotationDependencyLoaderService (precarga de entities relacionadas)
 *  - QuotationValidatorService (reglas de negocio)
 *  - QuotationCodeGeneratorService (secuencia anual con advisory lock)
 *  - QuotationCalculatorService (totales)
 *  - QuotationResponseAssemblerService (composicion del response jerarquico)
 *  - Repositorios (persistencia)
 *
 * Flow:
 *  1. Resolver usuario actual (created_by/updated_by).
 *  2. Precargar entities relacionadas (delegado a QuotationDependencyLoaderService).
 *  3. Validar reglas de negocio (QuotationValidatorService).
 *  4. Detectar duplicate anti doble-click (anti-spam window).
 *  5. Calcular totales (QuotationCalculatorService).
 *  6. Generar code (QuotationCodeGeneratorService — advisory lock + MAX en tx).
 *  7. Persistir header + items + standby_costs (atomico via @Transactional).
 *  8. Componer y devolver QuotationResponse (delegado a QuotationResponseAssemblerService).
 */
@ApplicationScoped
public class CreateQuotationService {

    private static final Logger LOG = Logger.getLogger(CreateQuotationService.class);

    @ConfigProperty(name = "app.quotations.anti-duplicate-window-seconds")
    int antiDuplicateWindowSeconds;

    @ConfigProperty(name = "app.quotations.default-igv-percentage", defaultValue = "18.00")
    BigDecimal defaultIgvPercentage;

    // Repos especificos del modulo (persistencia de la propia entity de quotation).
    @Inject QuotationRepository quotationRepository;
    @Inject QuotationItemRepository quotationItemRepository;
    @Inject QuotationStandbyCostRepository quotationStandbyCostRepository;
    @Inject UserRepository userRepository;

    // Services del modulo Quotations (cada uno con SRP, ver bitacora).
    @Inject QuotationDependencyLoaderService dependencyLoader;
    @Inject QuotationCodeGeneratorService codeGenerator;
    @Inject QuotationValidatorService validator;
    @Inject QuotationCalculatorService calculator;
    @Inject QuotationResponseAssemblerService assembler;
    @Inject AuthServiceMapper authServiceMapper;
    @Inject QuotationServiceMapper quotationServiceMapper;

    @Inject CurrentUser currentUser;

    @Transactional
    public QuotationResponse createQuotation(CreateQuotationCommand command) {
        Integer userId = currentUser.requireId();

        LoadedDependencies deps = dependencyLoader.loadFor(command);

        validator.validate(command, deps.serviceTypesById());

        // Lock por (createdBy, clientId) ANTES del check anti-duplicado para
        // cerrar la ventana TOCTOU: dos POST simultaneos del mismo usuario+cliente
        // quedan serializados aca, asi el segundo ve persistido al primero.
        // Se libera al commit/rollback de la tx (advisory_xact_lock).
        quotationRepository.acquireAntiDuplicateLock(userId, command.clientId());

        rejectIfRecentDuplicate(command, userId);

        QuotationCalculatorService.Totals totals = calculator.calculate(command.items());

        String code = codeGenerator.nextCode();

        Quotation quotation = persistQuotation(command, code, userId);
        List<QuotationItem> persistedItems = persistItems(command, quotation);
        Map<Long, QuotationStandbyCostResponse> standbyByItemId =
            persistStandbyCosts(command, quotation, persistedItems);

        User user = userRepository.findById(userId);
        UserResponse currentUserResponse = authServiceMapper.toUserResponse(user);
        // En CREATE, createdBy == updatedBy y la cotizacion recien-creada NO esta
        // expirada (isExpired siempre false al instante). El assembler recibe ambos
        // explicitamente para que pueda ser reusado en UPDATE/GET sin cambiar firma.
        return assembler.assemble(
            quotation, persistedItems, standbyByItemId, totals, deps,
            currentUserResponse, currentUserResponse, false
        );
    }

    // ---------- Anti-duplicado -----------------------------------------------

    private void rejectIfRecentDuplicate(CreateQuotationCommand command, Integer userId) {
        List<Quotation> recent = quotationRepository.findRecentByCreatedByAndClient(
            userId, command.clientId(), antiDuplicateWindowSeconds
        );
        if (recent.isEmpty()) return;

        Set<Integer> incomingTypes = new HashSet<>();
        for (CreateQuotationCommand.Item item : command.items()) {
            if (item.serviceTypeId() != null) incomingTypes.add(item.serviceTypeId());
        }

        List<Long> recentIds = recent.stream().map(q -> q.id).toList();
        Set<Integer> recentTypes = quotationItemRepository.serviceTypeIdsForQuotations(recentIds);

        if (recentTypes.containsAll(incomingTypes)) {
            LOG.warnf("Anti-duplicate triggered: createdBy=%d clientId=%d incomingTypes=%s recentTypes=%s recentCount=%d",
                userId, command.clientId(), incomingTypes, recentTypes, recent.size());
            throw QuotationsError.DUPLICATE_DETECTED.toException();
        }
    }

    // ---------- Persistencia -------------------------------------------------

    private Quotation persistQuotation(CreateQuotationCommand command, String code, Integer userId) {
        // Mapeo Command → Entity delegado al mapper (consistente con
        // ClientServiceMapper.toClientEntity / CargoTypeServiceMapper.toCargoTypeEntity).
        // El mapper setea status=DRAFT como constant + quotationType enum→name +
        // createdBy/updatedBy = userId.
        Quotation q = quotationServiceMapper.toQuotationEntity(command, code, userId);
        try {
            quotationRepository.persist(q);
            quotationRepository.flush();
        } catch (PersistenceException ex) {
            ConstraintViolationException cve = extractConstraintViolation(ex);
            if (cve != null && cve.getConstraintName() != null && cve.getConstraintName().contains("code")) {
                LOG.warnf("Race condition: UNIQUE code violation [code=%s]", code);
                throw QuotationsError.DUPLICATE_CODE.toException();
            }
            throw ex;
        }
        return q;
    }

    /**
     * Persiste items en DOS pasadas para evitar UPDATEs adicionales al commit:
     *
     *  <p><b>Pase 1</b>: persistir SOLO los roots (parentItemNumber=null). Tras
     *  el flush, los roots tienen su id asignado por la BD.
     *
     *  <p><b>Pase 2</b>: persistir los hijos con parentItemId YA seteado al
     *  parent.id correspondiente. Sin necesidad de mutar managed entities
     *  despues del flush (lo cual generaba N UPDATEs al commit por dirty
     *  checking de Hibernate).
     *
     *  <p>Para una cotizacion Integral con 4 hijos: antes = 5 INSERTs + 4 UPDATEs.
     *  Ahora = 5 INSERTs limpios. La lista devuelta preserva el orden original
     *  del command (no de persistencia) para que el assembler arme la jerarquia
     *  visualmente correcta.
     */
    private List<QuotationItem> persistItems(
            CreateQuotationCommand command, Quotation quotation) {
        boolean explicit = command.items().stream().allMatch(i -> i.itemNumber() != null);
        int itemCount = command.items().size();

        // Pre-allocate el array para que la lista devuelta respete el orden del command.
        QuotationItem[] persistedByCommandIndex = new QuotationItem[itemCount];
        Map<Integer, QuotationItem> rootByItemNumber = new HashMap<>();

        // Pase 1: persistir roots (parentItemNumber == null).
        for (int i = 0; i < itemCount; i++) {
            CreateQuotationCommand.Item ci = command.items().get(i);
            if (ci.parentItemNumber() != null) continue;  // skip children en pase 1
            QuotationItem qi = buildItemEntity(ci, quotation, i, explicit, null);
            quotationItemRepository.persist(qi);
            persistedByCommandIndex[i] = qi;
            rootByItemNumber.put(qi.itemNumber, qi);
        }
        quotationItemRepository.flush();  // garantiza ids asignados antes del pase 2.

        // Pase 2: persistir children con parentItemId ya resuelto.
        for (int i = 0; i < itemCount; i++) {
            CreateQuotationCommand.Item ci = command.items().get(i);
            if (ci.parentItemNumber() == null) continue;  // ya persistido en pase 1
            QuotationItem parent = rootByItemNumber.get(ci.parentItemNumber());
            if (parent == null) {
                // El validator deberia atrapar esto; defensa adicional.
                throw CommonError.VALIDATION_FAILED.toException(
                    "parentItemNumber=" + ci.parentItemNumber() + " no resuelve a un item persistido"
                );
            }
            QuotationItem qi = buildItemEntity(ci, quotation, i, explicit, parent.id);
            quotationItemRepository.persist(qi);
            persistedByCommandIndex[i] = qi;
        }

        return List.of(persistedByCommandIndex);
    }

    /**
     * Helper: construye una entidad QuotationItem desde un Item del command.
     * Comparte la logica de mapping para los dos pases de persistItems.
     */
    private QuotationItem buildItemEntity(
            CreateQuotationCommand.Item ci, Quotation quotation,
            int commandIndex, boolean explicitNumbers, Long parentItemId) {
        QuotationItem qi = new QuotationItem();
        qi.quotationId = quotation.id;
        qi.itemNumber = explicitNumbers ? ci.itemNumber() : (commandIndex + 1);
        qi.parentItemId = parentItemId;
        qi.quotationServiceTypeId = ci.serviceTypeId();
        qi.cargoTypeId = ci.cargoTypeId();
        qi.observations = ci.observations();
        qi.weightKg = ci.weightKg();
        qi.lengthMeters = ci.lengthMeters();
        qi.widthMeters = ci.widthMeters();
        qi.heightMeters = ci.heightMeters();
        qi.quantity = ci.quantity();
        qi.unitPrice = ci.unitPrice() != null ? ci.unitPrice() : BigDecimal.ZERO;
        qi.igvPercentage = defaultIgvPercentage;  // siempre del config — source of truth backend.
        qi.insuredAmount = ci.insuredAmount();
        qi.internalReferencePrice = ci.internalReferencePrice();
        return qi;
    }

    /**
     * Persiste los standby costs e inmediatamente los mapea a Response.
     * El assembler recibe el map ya-Response para mantener consistencia con
     * el resto de {@code LoadedDependencies} (todo Response, cero entities).
     * El mapeo es trivial (3 fields) — no justifica un mapper dedicado.
     */
    private Map<Long, QuotationStandbyCostResponse> persistStandbyCosts(
            CreateQuotationCommand command, Quotation quotation,
            List<QuotationItem> persistedItems) {
        Map<Long, QuotationStandbyCostResponse> byItemId = new HashMap<>();
        for (int i = 0; i < command.items().size(); i++) {
            CreateQuotationCommand.Standby sb = command.items().get(i).standby();
            if (sb == null) continue;
            QuotationItem item = persistedItems.get(i);
            QuotationStandbyCost cost = new QuotationStandbyCost();
            cost.quotationId = quotation.id;
            cost.quotationItemId = item.id;
            cost.pricePerDay = sb.pricePerDay();
            cost.includesIgv = sb.includesIgv() != null ? sb.includesIgv() : Boolean.FALSE;
            quotationStandbyCostRepository.persist(cost);
            byItemId.put(item.id, new QuotationStandbyCostResponse(
                cost.id, cost.pricePerDay, cost.includesIgv
            ));
        }
        return byItemId;
    }

    private ConstraintViolationException extractConstraintViolation(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) return cve;
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? cve : null;
    }
}
