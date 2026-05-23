package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculadora de totales de cotizacion. Pure function — no toca BD.
 * Inyecta el IGV default por config (consistente con {@link CreateQuotationService}).
 *
 * Reglas:
 *  - Subtotal por item = `unitPrice * quantity` (solo aplica a items ROOT).
 *  - Hijos del Servicio Integral NO entran al total (su unitPrice es 0,
 *    su subtotal es 0). Sus `internalReferencePrice` son referencia interna,
 *    no afectan el total visible al cliente.
 *  - IGV por item = `subtotal * (defaultIgvPercentage / 100)`. El IGV se
 *    resuelve SIEMPRE desde el config (el item NO trae el campo —
 *    decision de negocio: IGV nacional uniforme).
 *  - totalSubtotal = sum(items_root.subtotal).
 *  - totalIgv = sum(items_root.igv_amount).
 *  - totalAmount = totalSubtotal + totalIgv.
 *
 * Precision: BigDecimal con scale 2 (consistente con NUMERIC(12,2) de BD).
 * Rounding: HALF_UP (regla bancaria estandar).
 */
@ApplicationScoped
public class QuotationCalculatorService {

    private static final BigDecimal CIEN = new BigDecimal("100");
    private static final int SCALE = 2;

    @ConfigProperty(name = "app.quotations.default-igv-percentage", defaultValue = "18.00")
    BigDecimal defaultIgvPercentage;

    /**
     * Calcula los totales a partir de los items del command.
     * Los items hijos del Integral (con parentItemNumber != null) se ignoran
     * para el total — su contribucion es 0 por contrato del negocio.
     */
    public Totals calculate(List<CreateQuotationCommand.Item> items) {
        BigDecimal totalSubtotal = BigDecimal.ZERO;
        BigDecimal totalIgv = BigDecimal.ZERO;

        for (CreateQuotationCommand.Item item : items) {
            if (item.parentItemNumber() != null) {
                // Hijo del Integral: no contribuye al total.
                continue;
            }
            BigDecimal subtotal = subtotalFor(item);
            BigDecimal igv = igvFor(subtotal);
            totalSubtotal = totalSubtotal.add(subtotal);
            totalIgv = totalIgv.add(igv);
        }

        BigDecimal totalAmount = totalSubtotal.add(totalIgv);
        return new Totals(
            totalSubtotal.setScale(SCALE, RoundingMode.HALF_UP),
            totalIgv.setScale(SCALE, RoundingMode.HALF_UP),
            totalAmount.setScale(SCALE, RoundingMode.HALF_UP)
        );
    }

    /**
     * Subtotal para UN item: unitPrice * quantity.
     * Para hijos del Integral devuelve 0 (defensive — no deberian llegar
     * a este metodo si el caller filtra con parentItemNumber).
     */
    public BigDecimal subtotalFor(CreateQuotationCommand.Item item) {
        if (item.parentItemNumber() != null) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal unitPrice = item.unitPrice() != null ? item.unitPrice() : BigDecimal.ZERO;
        return unitPrice.multiply(BigDecimal.valueOf(item.quantity()))
            .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * IGV para un subtotal dado: {@code subtotal * (defaultIgvPercentage / 100)}.
     * El IGV se resuelve EXCLUSIVAMENTE desde config — el item NO lo trae mas
     * (decision de negocio: IGV es legal y nacional, source of truth backend).
     * El % se persiste como snapshot en cada item ({@code quotation_items.igv_percentage}).
     */
    private BigDecimal igvFor(BigDecimal subtotal) {
        return subtotal.multiply(defaultIgvPercentage).divide(CIEN, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Resultado de los calculos. Inmutable.
     */
    public record Totals(BigDecimal totalSubtotal, BigDecimal totalIgv, BigDecimal totalAmount) {}
}
