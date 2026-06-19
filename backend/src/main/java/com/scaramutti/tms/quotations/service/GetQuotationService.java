package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.auth.mapper.AuthServiceMapper;
import com.scaramutti.tms.quotations.QuotationsError;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationConditionSummary;
import com.scaramutti.tms.quotations.mapper.QuotationEmbeddedSummaryMapper;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.QuotationStandbyCost;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.repository.ConditionRepository;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationRepository;
import com.scaramutti.tms.shared.repository.QuotationStandbyCostRepository;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Servicio de lectura de cotizacion por id. Read-only — NO toca BD via write.
 *
 * <p>Orquesta los helpers ya existentes en el modulo:
 * <ol>
 *   <li>{@link QuotationRepository#findByIdOptional(Long)} — 404 si no existe.</li>
 *   <li>{@link QuotationItemRepository#findByQuotationId(Long)} — items ordenados.</li>
 *   <li>{@link QuotationStandbyCostRepository#findByQuotationId(Long)} — standby costs.</li>
 *   <li>{@link QuotationDependencyLoaderService#loadByIds(Integer, Integer, Integer, Set, Set)}
 *       — convierte FKs a Summaries sin validar isActive (cotizaciones viejas pueden
 *       referenciar entidades desactivadas).</li>
 *   <li>{@link QuotationCalculatorService#calculateFromEntities(List)} — totales
 *       usando el snapshot {@code igvPercentage} de cada item (no el config actual).</li>
 *   <li>Cargar createdBy/updatedBy users (dedup si coinciden — 1 query si son iguales).</li>
 *   <li>Derivar {@code isExpired} del estado persistido ({@code status == EXPIRED}, ADR-005).</li>
 *   <li>{@link QuotationResponseAssemblerService#assemble} — mismo helper que CREATE.</li>
 * </ol>
 *
 * <p>NO lleva {@code @Transactional}: lectura pura, Quarkus abre tx implicita si
 * Hibernate la necesita (mismo patron que {@code ClientService.findById}).
 *
 * <p>{@code isExpired} ya NO se computa por fechas en el read-path: se DERIVA del
 * {@code status} (lo mantiene {@code QuotationExpiryJob}). El assembler lo recibe por
 * parametro. Ver {@code GetQuotationServiceTest}.
 */
@ApplicationScoped
public class GetQuotationService {

    private static final Logger LOG = Logger.getLogger(GetQuotationService.class);

    @Inject QuotationRepository quotationRepository;
    @Inject QuotationItemRepository quotationItemRepository;
    @Inject QuotationStandbyCostRepository quotationStandbyCostRepository;
    @Inject ConditionRepository conditionRepository;
    @Inject UserRepository userRepository;

    @Inject QuotationDependencyLoaderService dependencyLoader;
    @Inject QuotationCalculatorService calculator;
    @Inject QuotationResponseAssemblerService assembler;
    @Inject QuotationEmbeddedSummaryMapper summaryMapper;
    @Inject AuthServiceMapper authServiceMapper;

    /**
     * Devuelve la cotizacion con el id dado. Lanza {@code QUO-003} (404) si
     * no existe. El detail incluye el id pedido para que el frontend muestre
     * el mensaje directo ({@code "La cotizacion con id 999 no existe"}).
     */
    public QuotationResponse getById(Long id) {
        Quotation quotation = quotationRepository.findByIdOptional(id)
            .orElseThrow(() -> QuotationsError.NOT_FOUND.toException(
                "La cotizacion con id " + id + " no existe"
            ));

        List<QuotationItem> items = quotationItemRepository.findByQuotationId(quotation.id);
        Map<Long, QuotationStandbyCostResponse> standbyByItemId = loadStandbyByItemId(quotation.id);

        Set<Integer> serviceTypeIds = collectServiceTypeIds(items);
        Set<Integer> cargoTypeIds = collectCargoTypeIds(items);

        LoadedDependencies deps = dependencyLoader.loadByIds(
            quotation.clientId, quotation.currencyId, quotation.paymentTermId,
            serviceTypeIds, cargoTypeIds
        );

        QuotationCalculatorService.Totals totals = calculator.calculateFromEntities(items);

        UserResponse createdBy = loadUser(quotation.createdBy);
        UserResponse updatedBy = quotation.createdBy.equals(quotation.updatedBy)
            ? createdBy                                       // dedup: 1 query
            : loadUser(quotation.updatedBy);

        boolean isExpired = computeIsExpired(quotation);

        List<QuotationConditionSummary> conditions =
            summaryMapper.toConditionSummaries(conditionRepository.findLinkedToQuotation(quotation.id));

        return assembler.assemble(
            quotation, items, standbyByItemId, conditions, totals, deps,
            createdBy, updatedBy, isExpired
        );
    }

    /**
     * Carga los standby costs de la cotizacion y los indexa por
     * {@code quotationItemId} (la cardinalidad es 0..1 por item — UNIQUE
     * constraint en BD). El mapping inline es trivial (3 fields) — no
     * justifica un mapper dedicado.
     */
    private Map<Long, QuotationStandbyCostResponse> loadStandbyByItemId(Long quotationId) {
        Map<Long, QuotationStandbyCostResponse> byItemId = new HashMap<>();
        for (QuotationStandbyCost sb : quotationStandbyCostRepository.findByQuotationId(quotationId)) {
            byItemId.put(sb.quotationItemId,
                new QuotationStandbyCostResponse(sb.id, sb.pricePerDay, sb.includesIgv));
        }
        return byItemId;
    }

    /**
     * Carga el {@link User} por id. Si no existe (FK huerfana porque alguien
     * borro el row de {@code users}), es bug de integridad referencial — log
     * error + COM-500. Mismo tratamiento que las otras FKs en
     * {@link QuotationDependencyLoaderService#loadByIds(Integer, Integer, Integer, java.util.Set, java.util.Set)}.
     */
    private UserResponse loadUser(Integer userId) {
        User user = userRepository.findById(userId);
        if (user == null) {
            LOG.errorf("Orphan FK in quotation READ path: user not found, userId=%s — la cotizacion referencia un usuario que ya no existe", userId);
            throw CommonError.INTERNAL_ERROR.toException(
                "La cotizacion referencia un usuario inexistente (createdBy/updatedBy id=" + userId + "). Reporte a soporte."
            );
        }
        return authServiceMapper.toUserResponse(user);
    }

    /**
     * {@code isExpired} se DERIVA del estado persistido (ADR-005): {@code true} si y solo si
     * {@code status == EXPIRED}. Una sola fuente de verdad — el {@code status} que mantiene el
     * job de vencimiento ({@code QuotationExpiryJob}), NO un calculo por fechas en el read-path.
     *
     * <p>Matiz (ventana ≤24h): una {@code SENT} cuya validez ya paso sigue reportando
     * {@code isExpired=false} hasta la proxima corrida del job (que la pasa a {@code EXPIRED}).
     * El campo se mantiene en el response por compatibilidad con el frontend.
     */
    private boolean computeIsExpired(Quotation quotation) {
        return QuotationStatus.EXPIRED.name().equals(quotation.status);
    }

    private Set<Integer> collectServiceTypeIds(List<QuotationItem> items) {
        Set<Integer> ids = new HashSet<>();
        for (QuotationItem item : items) {
            if (item.quotationServiceTypeId != null) ids.add(item.quotationServiceTypeId);
        }
        return ids;
    }

    private Set<Integer> collectCargoTypeIds(List<QuotationItem> items) {
        Set<Integer> ids = new HashSet<>();
        for (QuotationItem item : items) {
            if (item.cargoTypeId != null) ids.add(item.cargoTypeId);
        }
        return ids;
    }
}
