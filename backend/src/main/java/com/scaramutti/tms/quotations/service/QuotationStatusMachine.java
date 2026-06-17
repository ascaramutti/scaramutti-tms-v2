package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.quotations.model.QuotationStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Maquina de estados de cotizacion (ADR-004). Stateless: una sola fuente de
 * verdad de que transicion es legal, materializada como un unico mapa
 * {@code origen → {destinos validos}}.
 *
 * <pre>
 *   DRAFT ──"Enviar"──▶ SENT ──"Aceptar"──▶ ACCEPTED  (terminal)
 *                        │
 *                        ├──"Rechazar"────▶ REJECTED  (terminal)
 *                        │
 *                        └──job vencida───▶ EXPIRED   (terminal)
 * </pre>
 *
 * <p>Consumida por el PATCH (transiciones de usuario: {@code SENT}, {@code ACCEPTED},
 * {@code REJECTED}) y, a futuro, por el job de vencimiento (la transicion de sistema
 * {@code SENT → EXPIRED} — slice 2). Por eso {@code SENT → EXPIRED} figura como valida
 * en el mapa aunque el PATCH no exponga {@code EXPIRED} como destino de usuario (eso lo
 * acota el enum del body, no la maquina).
 *
 * <p>Los estados terminales ({@code ACCEPTED}/{@code REJECTED}/{@code EXPIRED}) no
 * tienen destinos: toda salida de un terminal es invalida (inmutabilidad, ADR-004).
 */
@ApplicationScoped
public class QuotationStatusMachine {

    /**
     * Mapa unico de transiciones validas. {@code DRAFT → {SENT}}; {@code SENT →
     * {ACCEPTED, REJECTED, EXPIRED}}; terminales → conjunto vacio. Inmutable
     * (se construye una vez al cargar la clase).
     */
    private static final Map<QuotationStatus, Set<QuotationStatus>> TRANSITIONS;

    static {
        Map<QuotationStatus, Set<QuotationStatus>> map = new EnumMap<>(QuotationStatus.class);
        map.put(QuotationStatus.DRAFT, EnumSet.of(QuotationStatus.SENT));
        map.put(QuotationStatus.SENT, EnumSet.of(
            QuotationStatus.ACCEPTED, QuotationStatus.REJECTED, QuotationStatus.EXPIRED));
        map.put(QuotationStatus.ACCEPTED, EnumSet.noneOf(QuotationStatus.class));
        map.put(QuotationStatus.REJECTED, EnumSet.noneOf(QuotationStatus.class));
        map.put(QuotationStatus.EXPIRED, EnumSet.noneOf(QuotationStatus.class));
        TRANSITIONS = map;
    }

    /**
     * {@code true} si la transicion {@code from → to} es legal segun la maquina.
     * Una transicion a si mismo (ej. {@code SENT → SENT}) es invalida (no figura
     * en el conjunto de destinos del origen).
     */
    public boolean canTransition(QuotationStatus from, QuotationStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
