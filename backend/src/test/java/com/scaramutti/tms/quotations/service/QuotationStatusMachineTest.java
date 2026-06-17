package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.model.QuotationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit puro de {@link QuotationStatusMachine} (no necesita Quarkus ni BD). Hace cumplir
 * la maquina de estados de ADR-004: {@code DRAFT → SENT → {ACCEPTED|REJECTED|EXPIRED}},
 * terminales sin salida. {@code SENT → EXPIRED} figura como valida (es la transicion de
 * sistema del job, slice 2) aunque el PATCH no la exponga como destino de usuario.
 */
class QuotationStatusMachineTest {

    private final QuotationStatusMachine machine = new QuotationStatusMachine();

    // ---------- Transiciones validas -----------------------------------------

    @ParameterizedTest
    @CsvSource({
        "DRAFT, SENT",
        "SENT, ACCEPTED",
        "SENT, REJECTED",
        "SENT, EXPIRED"   // transicion de sistema del job (no de usuario)
    })
    void validTransitions_returnTrue(QuotationStatus from, QuotationStatus to) {
        assertTrue(machine.canTransition(from, to),
            "Esperaba transicion valida: " + from + " -> " + to);
    }

    // ---------- Transiciones invalidas ---------------------------------------

    @ParameterizedTest
    @CsvSource({
        // DRAFT no puede saltar directo a terminales
        "DRAFT, ACCEPTED",
        "DRAFT, REJECTED",
        "DRAFT, EXPIRED",
        // No se vuelve a DRAFT
        "SENT, DRAFT",
        // Self-transition no es valida
        "DRAFT, DRAFT",
        "SENT, SENT",
        // Salir de un terminal nunca es valido
        "ACCEPTED, SENT",
        "ACCEPTED, REJECTED",
        "ACCEPTED, EXPIRED",
        "REJECTED, SENT",
        "REJECTED, ACCEPTED",
        "REJECTED, DRAFT",
        "EXPIRED, SENT",
        "EXPIRED, ACCEPTED",
        "EXPIRED, DRAFT"
    })
    void invalidTransitions_returnFalse(QuotationStatus from, QuotationStatus to) {
        assertFalse(machine.canTransition(from, to),
            "Esperaba transicion invalida: " + from + " -> " + to);
    }

    // ---------- Terminales: ningun destino -----------------------------------

    @ParameterizedTest
    @EnumSource(value = QuotationStatus.class, names = {"ACCEPTED", "REJECTED", "EXPIRED"})
    void terminalStates_haveNoValidDestination(QuotationStatus terminal) {
        for (QuotationStatus to : QuotationStatus.values()) {
            assertFalse(machine.canTransition(terminal, to),
                "Un estado terminal (" + terminal + ") no debe transicionar a nada (intento: " + to + ")");
        }
        assertTrue(terminal.isTerminal(), terminal + " debe ser terminal");
    }

    // ---------- isTerminal del enum (cobertura directa) ----------------------

    @Test
    void isTerminal_onlyForAcceptedRejectedExpired() {
        assertFalse(QuotationStatus.DRAFT.isTerminal());
        assertFalse(QuotationStatus.SENT.isTerminal());
        assertTrue(QuotationStatus.ACCEPTED.isTerminal());
        assertTrue(QuotationStatus.REJECTED.isTerminal());
        assertTrue(QuotationStatus.EXPIRED.isTerminal());
    }
}
