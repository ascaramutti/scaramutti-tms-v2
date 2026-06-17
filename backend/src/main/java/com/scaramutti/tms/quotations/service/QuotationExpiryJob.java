package com.scaramutti.tms.quotations.service;

import com.scaramutti.tms.shared.repository.QuotationRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Job de vencimiento de cotizaciones (ADR-005). Parte del CORE del backend (no un
 * slice aparte): corre 1×/dia de madrugada y flipea {@code SENT → EXPIRED} las
 * cotizaciones cuya validez ya paso. Asi el {@code status} persistido es la unica
 * fuente de verdad de "Vencida" (el read-path deriva {@code isExpired} de el).
 *
 * <p>El flip es un bulk {@code UPDATE} idempotente que toca SOLO {@code status} (no
 * {@code updated_at}/{@code updated_by}: la expiracion es del sistema, no un cambio de
 * usuario) — ver {@link QuotationRepository#expireSentQuotations()}.
 *
 * <p>Frecuencia: {@code 0 0 3 * * ?} en zona {@code America/Lima} = 03:00 hora Peru,
 * todos los dias. Sintaxis Quartz (cron-type default de quarkus-scheduler): los 6
 * campos son {@code segundo minuto hora dia-del-mes mes dia-de-la-semana}; {@code ?}
 * en dia-de-la-semana es obligatorio cuando dia-del-mes es {@code *} (Quartz no admite
 * ambos con {@code *}).
 *
 * <p>Consecuencia documentada (ventana ≤24h): una {@code SENT} que vence a media manana
 * sigue figurando como {@code SENT} (con {@code isExpired=false}) hasta la corrida de las
 * 03:00 del dia siguiente, cuando pasa a {@code EXPIRED}. Trade-off aceptado del job.
 *
 * <p>Concurrencia: deploy single-node (ver {@code prod_topology}), no necesita lock
 * distribuido. Si en el futuro hay >1 instancia, agregar
 * {@code concurrentExecution = ConcurrentExecution.SKIP}.
 */
@ApplicationScoped
public class QuotationExpiryJob {

    private static final Logger LOG = Logger.getLogger(QuotationExpiryJob.class);

    @Inject QuotationRepository quotationRepository;

    /**
     * Corre el flip de vencimiento. Loguea cuantas cotizaciones marco {@code EXPIRED}
     * (idempotente: lo normal es 0 salvo el dia que alguna {@code SENT} cruza su validez).
     */
    @Scheduled(cron = "0 0 3 * * ?", timeZone = "America/Lima")
    public void expireQuotations() {
        int expired = quotationRepository.expireSentQuotations();
        if (expired > 0) {
            LOG.infof("Quotation expiry job: marked %d SENT quotation(s) as EXPIRED", expired);
        } else {
            LOG.debug("Quotation expiry job: no SENT quotations past validity");
        }
    }
}
