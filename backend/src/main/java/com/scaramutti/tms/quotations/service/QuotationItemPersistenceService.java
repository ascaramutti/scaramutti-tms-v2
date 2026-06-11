package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.dto.QuotationStandbyCostResponse;
import com.scaramutti.tms.quotations.service.cmd.SaveQuotationCommand;
import com.scaramutti.tms.shared.entity.Quotation;
import com.scaramutti.tms.shared.entity.QuotationItem;
import com.scaramutti.tms.shared.entity.QuotationStandbyCost;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.QuotationItemRepository;
import com.scaramutti.tms.shared.repository.QuotationStandbyCostRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persiste los items (jerarquia root + hijos del Integral) y los standby costs de
 * una cotizacion. Extraido de {@link CreateQuotationService} para compartir la logica
 * entre crear y editar: el UPDATE reemplaza los items (borra los viejos e inserta los
 * nuevos con esta misma logica de 2 pases). Una sola fuente para el algoritmo no
 * trivial — evita drift entre los dos flujos.
 */
@ApplicationScoped
public class QuotationItemPersistenceService {

    @ConfigProperty(name = "app.quotations.default-igv-percentage")
    BigDecimal defaultIgvPercentage;

    @Inject QuotationItemRepository quotationItemRepository;
    @Inject QuotationStandbyCostRepository quotationStandbyCostRepository;

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
    public List<QuotationItem> persistItems(SaveQuotationCommand command, Quotation quotation) {
        boolean explicit = command.items().stream().allMatch(i -> i.itemNumber() != null);
        int itemCount = command.items().size();

        // Pre-allocate el array para que la lista devuelta respete el orden del command.
        QuotationItem[] persistedByCommandIndex = new QuotationItem[itemCount];
        Map<Integer, QuotationItem> rootByItemNumber = new HashMap<>();

        // Pase 1: persistir roots (parentItemNumber == null).
        for (int i = 0; i < itemCount; i++) {
            SaveQuotationCommand.Item ci = command.items().get(i);
            if (ci.parentItemNumber() != null) continue;  // skip children en pase 1
            QuotationItem qi = buildItemEntity(ci, quotation, i, explicit, null);
            quotationItemRepository.persist(qi);
            persistedByCommandIndex[i] = qi;
            rootByItemNumber.put(qi.itemNumber, qi);
        }
        quotationItemRepository.flush();  // garantiza ids asignados antes del pase 2.

        // Pase 2: persistir children con parentItemId ya resuelto.
        for (int i = 0; i < itemCount; i++) {
            SaveQuotationCommand.Item ci = command.items().get(i);
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
            SaveQuotationCommand.Item ci, Quotation quotation,
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
    public Map<Long, QuotationStandbyCostResponse> persistStandbyCosts(
            SaveQuotationCommand command, Quotation quotation,
            List<QuotationItem> persistedItems) {
        Map<Long, QuotationStandbyCostResponse> byItemId = new HashMap<>();
        for (int i = 0; i < command.items().size(); i++) {
            SaveQuotationCommand.Standby sb = command.items().get(i).standby();
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
}
