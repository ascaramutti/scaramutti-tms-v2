package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.service.cmd.CreateQuotationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit puro del calculator — sin Quarkus, sin mocks. Cubre edge cases de
 * los totales (subtotal/IGV/total) que son el corazon del precio al cliente.
 *
 * <p>El {@code defaultIgvPercentage} normalmente viene de {@code @ConfigProperty};
 * en el test lo seteamos manualmente (field package-private accesible desde el
 * mismo package). El IGV se resuelve EXCLUSIVAMENTE desde el config — el item
 * ya no trae {@code igvPercentage} (decision de negocio: IGV nacional uniforme).
 */
class QuotationCalculatorServiceTest {

    private final QuotationCalculatorService calculator = new QuotationCalculatorService();

    @BeforeEach
    void initConfig() {
        calculator.defaultIgvPercentage = new BigDecimal("18.00");
    }

    /**
     * Helper sin {@code igvPercentage} — el calculator lee el % del config,
     * no del item.
     */
    private CreateQuotationCommand.Item item(BigDecimal unitPrice, int qty, Integer parentItemNumber) {
        return new CreateQuotationCommand.Item(
            null, parentItemNumber, 1, null, null,
            null, null, null, null, qty, unitPrice, null, null, null
        );
    }

    @Test
    void calculate_singleRootItem_returnsExpectedTotals() {
        CreateQuotationCommand.Item only = item(new BigDecimal("1000.00"), 1, null);
        QuotationCalculatorService.Totals t = calculator.calculate(List.of(only));
        assertEquals(new BigDecimal("1000.00"), t.totalSubtotal());
        assertEquals(new BigDecimal("180.00"), t.totalIgv());
        assertEquals(new BigDecimal("1180.00"), t.totalAmount());
    }

    @Test
    void calculate_multipleRootItems_sumsCorrectly() {
        var items = List.of(
            item(new BigDecimal("500.00"), 2, null),   // 1000 + 180 IGV
            item(new BigDecimal("100.00"), 5, null)    // 500 + 90 IGV
        );
        var t = calculator.calculate(items);
        assertEquals(new BigDecimal("1500.00"), t.totalSubtotal());
        assertEquals(new BigDecimal("270.00"), t.totalIgv());
        assertEquals(new BigDecimal("1770.00"), t.totalAmount());
    }

    @Test
    void calculate_childrenOfIntegral_doNotContributeToTotal() {
        var items = List.of(
            item(new BigDecimal("5000.00"), 1, null),   // root INT: contribuye
            item(new BigDecimal("3000.00"), 1, 1),      // hijo: ignorado
            item(new BigDecimal("1000.00"), 1, 1)       // hijo: ignorado
        );
        var t = calculator.calculate(items);
        assertEquals(new BigDecimal("5000.00"), t.totalSubtotal(), "solo el root cuenta");
        assertEquals(new BigDecimal("900.00"), t.totalIgv());
        assertEquals(new BigDecimal("5900.00"), t.totalAmount());
    }

    @Test
    void calculate_zeroQuantity_yieldsZeroSubtotal() {
        // En la practica Bean Validation @Min(1) lo bloquea, pero defensive test.
        var items = List.of(item(new BigDecimal("100.00"), 0, null));
        var t = calculator.calculate(items);
        assertEquals(new BigDecimal("0.00"), t.totalSubtotal());
        assertEquals(new BigDecimal("0.00"), t.totalIgv());
    }

    @Test
    void calculate_withConfigIgvZero_yieldsZeroIgv() {
        // Si el config setea IGV=0 (hipotetico, ej. exoneracion legal futura),
        // el calculator devuelve totalIgv=0 sin tocar el item.
        calculator.defaultIgvPercentage = BigDecimal.ZERO;
        var items = List.of(item(new BigDecimal("100.00"), 1, null));
        var t = calculator.calculate(items);
        assertEquals(new BigDecimal("100.00"), t.totalSubtotal());
        assertEquals(new BigDecimal("0.00"), t.totalIgv());
        assertEquals(new BigDecimal("100.00"), t.totalAmount());
    }

    @Test
    void calculate_emptyItemList_returnsAllZeros() {
        var t = calculator.calculate(List.of());
        assertEquals(new BigDecimal("0.00"), t.totalSubtotal());
        assertEquals(new BigDecimal("0.00"), t.totalIgv());
        assertEquals(new BigDecimal("0.00"), t.totalAmount());
    }

    @Test
    void calculate_decimalPrecision_roundsHalfUp() {
        // 33.333... * 3 = 99.999..., IGV 18% = 17.99982 → redondea a 18.00.
        var items = List.of(item(new BigDecimal("33.33"), 3, null));
        var t = calculator.calculate(items);
        assertEquals(new BigDecimal("99.99"), t.totalSubtotal());
        assertEquals(new BigDecimal("18.00"), t.totalIgv());
        assertEquals(new BigDecimal("117.99"), t.totalAmount());
    }
}
