package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.quotations.dto.QuotationItemResponse;
import com.scaramutti.tms.quotations.dto.QuotationResponse;
import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.dto.embedded.QuotationCargoTypeSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationConditionSummary;
import com.scaramutti.tms.quotations.dto.embedded.QuotationServiceTypeSummary;
import com.scaramutti.tms.quotations.model.QuotationStatus;
import com.scaramutti.tms.quotations.model.QuotationType;
import com.scaramutti.tms.quotations.service.QuotationDependencyLoaderService.LoadedDependencies;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ensambla el {@link QuotationResponse} final a partir de las entidades
 * persistidas + dependencias precargadas + totales calculados.
 *
 * <p>SRP: este service NO valida, NO calcula, NO persiste — solo ensambla.
 * Es <b>determinista</b>: dados los mismos inputs, produce el mismo output
 * (sin llamadas a {@code now()} ni a mappers externos). Si {@code isExpired}
 * depende del momento, el caller lo calcula y lo pasa como parametro.
 *
 * <p><b>Por que es un Assembler (manual) y NO un Mapper (MapStruct):</b>
 * el patron del proyecto es "1 entity → 1 Response = usar MapStruct"
 * (ver ClientServiceMapper, CargoTypeServiceMapper, etc.). Este caso NO
 * encaja en ese patron porque combina:
 * <ul>
 *   <li><b>Multiples fuentes</b>: Quotation entity, LoadedDependencies,
 *       Totals, dos UserResponse (created/updated), persistedItems,
 *       standby map ya-Response.</li>
 *   <li><b>Campo computado en runtime por el caller</b>: {@code expiresAt}
 *       se computa aca pero {@code isExpired} se recibe (decision del caller).</li>
 *   <li><b>Algoritmo de 2 pasadas</b> para items jerarquicos (root + children
 *       embedded) — no es mapping, es logica algoritmica.</li>
 * </ul>
 * Migrar a MapStruct exigiria ~15 {@code expression = "java(...)"} inline
 * (strings sin tipado, refactor-unsafe) sin reducir LOC, y el algoritmo
 * jerarquico igual tendria que vivir como default method. La construccion
 * manual aca es <em>composicion</em>, no <em>mapping</em>.
 *
 * <p>El standby llega como {@code Map<Long, QuotationStandbyCostResponse>}
 * (ya-Response, mapeado inline en {@code CreateQuotationService.persistStandbyCosts}
 * — el mapeo de 3 fields no justifica un mapper dedicado). Esto deja al
 * assembler 100% libre de mappers — solo composicion pura.
 */
@ApplicationScoped
public class QuotationResponseAssemblerService {

    private static final Logger LOG = Logger.getLogger(QuotationResponseAssemblerService.class);

    /**
     * Construye el response final.
     *
     * @param quotation         entity persistida
     * @param persistedItems    items persistidos en orden de insercion
     * @param standbyByItemId   standby costs (ya-Response) indexados por quotationItemId
     * @param conditions        condiciones generales linkeadas (ya-Summary, ordenadas por displayOrder; incluye inactivas, RN-05)
     * @param totals            totales calculados por QuotationCalculatorService
     * @param deps              dependencias precargadas (Response DTOs)
     * @param createdBy         usuario que crea la cotizacion
     * @param updatedBy         usuario que actualiza (en CREATE coincide con createdBy)
     * @param isExpired         si la cotizacion ya vencio (el caller decide; CREATE pasa false)
     */
    public QuotationResponse assemble(
            Quotation quotation,
            List<QuotationItem> persistedItems,
            Map<Long, QuotationStandbyCostResponse> standbyByItemId,
            List<QuotationConditionSummary> conditions,
            QuotationCalculatorService.Totals totals,
            LoadedDependencies deps,
            UserResponse createdBy,
            UserResponse updatedBy,
            boolean isExpired) {

        List<QuotationItemResponse> items = buildHierarchicalItems(persistedItems, standbyByItemId, deps);

        OffsetDateTime expiresAt = quotation.createdAt.plusDays(quotation.validityDays);

        return new QuotationResponse(
            quotation.id,
            quotation.code,
            QuotationType.valueOf(quotation.quotationType),
            QuotationStatus.valueOf(quotation.status),
            deps.client(),
            quotation.contactName,
            quotation.contactPhone,
            deps.currency(),
            deps.paymentTerm(),
            quotation.tentativeServiceDate,
            quotation.validityDays,
            expiresAt,
            isExpired,
            quotation.origin,
            quotation.destination,
            quotation.clientNote,
            quotation.internalNote,
            quotation.rejectionReason,
            totals.totalSubtotal(),
            totals.totalIgv(),
            totals.totalAmount(),
            items,
            conditions,
            createdBy,
            updatedBy,
            quotation.createdAt,
            quotation.updatedAt
        );
    }

    /**
     * Two-pass:
     *  1. Crea response shallow por cada item (children=null).
     *  2. Re-construye root items con children embebidos (records inmutables).
     *
     * <p>Defensive: si tras la asignacion de children quedan entries en
     * {@code childrenByParent} cuyo parent NO esta entre los items root,
     * significa que el caller paso items huerfanos (bug upstream — el
     * validator deberia atraparlo en el request, y persistItems setea
     * parentItemId desde un parent persistido en la misma tx). Logueamos
     * warning y descartamos esos children — no rompemos la cotizacion pero
     * dejamos rastro para detectar el bug en runtime.
     */
    private List<QuotationItemResponse> buildHierarchicalItems(
            List<QuotationItem> persistedItems,
            Map<Long, QuotationStandbyCostResponse> standbyByItemId,
            LoadedDependencies deps) {

        List<QuotationItemResponse> rootResponses = new ArrayList<>();
        Map<Long, List<QuotationItemResponse>> childrenByParent = new HashMap<>();

        for (QuotationItem item : persistedItems) {
            QuotationServiceTypeSummary serviceTypeResp = deps.serviceTypesById().get(item.quotationServiceTypeId);
            QuotationCargoTypeSummary cargoResp = item.cargoTypeId != null
                ? deps.cargoTypesById().get(item.cargoTypeId)
                : null;
            QuotationStandbyCostResponse standbyResp = standbyByItemId.get(item.id);

            BigDecimal subtotal = item.parentItemId != null
                ? BigDecimal.ZERO
                : item.unitPrice.multiply(BigDecimal.valueOf(item.quantity));

            QuotationItemResponse resp = new QuotationItemResponse(
                item.id, item.parentItemId, item.itemNumber, null /* displayLabel: pasada 2 */,
                serviceTypeResp, cargoResp,
                item.observations, item.weightKg, item.lengthMeters, item.widthMeters, item.heightMeters,
                item.quantity, item.unitPrice, item.internalReferencePrice,
                item.igvPercentage, subtotal, item.insuredAmount, standbyResp,
                null
            );
            if (item.parentItemId == null) {
                rootResponses.add(resp);
            } else {
                childrenByParent.computeIfAbsent(item.parentItemId, k -> new ArrayList<>()).add(resp);
            }
        }

        // Pasada 2: numeracion de presentacion jerarquica + children embebidos. El displayLabel
        // se deriva de la POSICION (no del itemNumber plano: un root posterior a un Integral es
        // "2" aunque su itemNumber sea 4, porque los hijos del Integral consumen 2 y 3). Root ->
        // "1","2",...; hijo -> "1.a","1.b",... Los roots vienen en orden de itemNumber (insercion).
        List<QuotationItemResponse> finalItems = new ArrayList<>();
        int rootPosition = 0;
        for (QuotationItemResponse root : rootResponses) {
            rootPosition++;
            String rootLabel = String.valueOf(rootPosition);
            List<QuotationItemResponse> children = childrenByParent.remove(root.id());
            List<QuotationItemResponse> labeledChildren = null;
            if (children != null && !children.isEmpty()) {
                labeledChildren = new ArrayList<>(children.size());
                for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                    String childLabel = rootLabel + "." + childLetter(childIndex);
                    labeledChildren.add(withDisplayLabel(children.get(childIndex), childLabel, null));
                }
            }
            finalItems.add(withDisplayLabel(root, rootLabel, labeledChildren));
        }

        // Invariante violada: cualquier entry remanente en childrenByParent es un
        // huerfano (child cuyo parent NO esta en persistedItems). En CREATE NO debe
        // pasar — persistItems setea parentItemId desde un parent persistido en la
        // misma tx. Si llega aca es bug upstream serio.
        // Fail-fast con IllegalStateException — el assembler corre DENTRO del
        // @Transactional del orquestador, asi que la cotizacion entera hace
        // rollback automatico. Cero datos inconsistentes en BD. El 500 + log
        // sirve como alerta para investigar el bug upstream.
        if (!childrenByParent.isEmpty()) {
            LOG.errorf("INVARIANT VIOLATION: orphan items in buildHierarchicalItems: parentItemIds=%s",
                childrenByParent.keySet());
            throw new IllegalStateException(
                "Orphan items detected in quotation response assembly: parentItemIds="
                    + childrenByParent.keySet()
                    + ". Quotation creation rolled back — investigate upstream."
            );
        }

        return finalItems;
    }

    /** Reconstruye el item (record inmutable) con su displayLabel y children embebidos. */
    private static QuotationItemResponse withDisplayLabel(
            QuotationItemResponse item, String displayLabel, List<QuotationItemResponse> children) {
        return new QuotationItemResponse(
            item.id(), item.parentItemId(), item.itemNumber(), displayLabel, item.serviceType(),
            item.cargoType(), item.observations(), item.weightKg(), item.lengthMeters(),
            item.widthMeters(), item.heightMeters(), item.quantity(), item.unitPrice(),
            item.internalReferencePrice(), item.igvPercentage(), item.subtotal(),
            item.insuredAmount(), item.standby(), children
        );
    }

    /** Letra del hijo por indice: 0->a, 1->b, ... Se asume <26 hijos por Integral
     * (el negocio no arma paquetes con tantos componentes). */
    private static char childLetter(int childIndex) {
        return (char) ('a' + childIndex);
    }
}
