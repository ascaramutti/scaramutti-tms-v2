package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.ServicesReportResponse;
import com.scaramutti.tms.operations.dto.ServicesReportRowResponse;
import com.scaramutti.tms.operations.dto.ServicesReportTotalsResponse;
import com.scaramutti.tms.operations.dto.embedded.ServiceAdditionalDriverSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceWeekCycleSummary;
import com.scaramutti.tms.operations.mapper.ServiceServiceMapper;
import com.scaramutti.tms.operations.service.cmd.GetServicesReportQuery;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ServiceReportRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El reporte de facturacion de UNA semana operativa: que viajes se cerraron y cuanto se cobro por ellos.
 *
 * <p><b>{@code @Transactional} compra UNA SOLA COSA aca: que las dos lecturas compartan conexion.</b>
 * NO compra consistencia entre ellas: bajo READ COMMITTED —el nivel por defecto— cada sentencia
 * toma su propio snapshot. Una version anterior de este javadoc afirmaba que el peor caso era un
 * refuerzo huerfano "y nunca una fila sin sus datos", y era falso: con la segunda consulta filtrando
 * otra vez por la ventana de la semana, bastaba con que alguien corrigiera la fecha de fin de un viaje entre una y
 * otra para publicar la fila con la lista de refuerzos VACIA, en silencio.
 *
 * <p>Lo que cierra ese agujero no es el aislamiento sino la FORMA: los refuerzos se piden por los
 * ids que devolvio la primera consulta, asi que corresponden a las filas publicadas por
 * construccion. Es el mismo criterio con el que los totales se suman sobre las filas ya armadas.
 */
@ApplicationScoped
public class GetServicesReportService {

    private static final Logger LOG = Logger.getLogger(GetServicesReportService.class);

    @Inject
    ServiceReportRepository serviceReportRepository;

    @Inject
    ServiceServiceMapper serviceServiceMapper;

    @Inject
    ServicePriceVisibility servicePriceVisibility;

    // ⚠️ Ninguna prueba distingue que esta anotacion este: las dos consultas son lecturas nativas y
    // corren igual sin transaccion. Se conserva por lo que dice el javadoc de arriba (que las dos
    // compartan conexion), no porque haya una red que lo sostenga. Mismo criterio de honestidad que
    // las notas de `a.id` y `a.service_id` en el ORDER BY de refuerzos.
    @Transactional
    public ServicesReportResponse getServicesReport(GetServicesReportQuery getServicesReportQuery) {
        // El VETO va primero, antes de tocar la base: a quien no puede ver importes se le niega el
        // reporte entero. No es redundante con la lista de roles del recurso: aquella es un O y esto
        // es un VETO, asi que un usuario que sumara despacho y ventas entraria por la lista.
        servicePriceVisibility.requireCanSeePrices();

        ServiceWeekCycleSummary weekCycle =
            OperationsWeekCycle.startingOn(getServicesReportQuery.weekStart());
        OffsetDateTime fromInclusive = OperationsWeekCycle.startInstant(weekCycle);
        OffsetDateTime toExclusive = OperationsWeekCycle.endExclusiveInstant(weekCycle);

        List<ServiceReportRepository.ServicesReportRow> reportRows =
            serviceReportRepository.findCompletedInRange(fromInclusive, toExclusive);
        // Los refuerzos se piden por los IDS que la consulta de arriba devolvio, no por la ventana:
        // asi corresponden a las filas que se van a publicar por construccion. Ver el javadoc del
        // repositorio para el escenario concreto que esto cierra.
        Map<Long, List<ServiceAdditionalDriverSummary>> additionalDriversByService =
            groupAdditionalDrivers(serviceReportRepository.findAdditionalDriversOf(
                reportRows.stream().map(ServiceReportRepository.ServicesReportRow::serviceId).toList()));

        List<ServicesReportRowResponse> rows = new ArrayList<>(reportRows.size());
        for (ServiceReportRepository.ServicesReportRow reportRow : reportRows) {
            requireCompleteRow(reportRow);
            rows.add(serviceServiceMapper.toServicesReportRowResponse(
                reportRow,
                additionalDriversByService.getOrDefault(reportRow.serviceId(), List.of())));
        }

        return new ServicesReportResponse(
            weekCycle,
            OperationsWeekCycle.hasClosed(weekCycle, Instant.now()),
            rows,
            toServicesReportTotals(rows));
    }

    /**
     * Los refuerzos de todos los viajes, indexados por viaje. Vienen ya ordenados de la consulta y
     * cada LISTA conserva ese orden, para que el mismo relevo se lea igual que en el detalle. (El
     * orden de las claves del mapa no lo observa nadie: se consulta por id, nunca se recorre.)
     */
    private Map<Long, List<ServiceAdditionalDriverSummary>> groupAdditionalDrivers(
            List<ServiceReportRepository.ServiceAdditionalDriverRow> additionalDriverRows) {
        // ⚠️ Que este mapa preserve el orden NO lo distingue ninguna prueba: se consulta por id y
        // nunca se recorre, asi que un HashMap daria igual. Es lo contrario del mapa de los totales,
        // donde el orden SI es contrato y dos casos lo matan. Se deja LinkedHashMap por simetria de
        // lectura, no porque haya red.
        Map<Long, List<ServiceAdditionalDriverSummary>> byService = new LinkedHashMap<>();
        for (ServiceReportRepository.ServiceAdditionalDriverRow additionalDriverRow : additionalDriverRows) {
            byService
                .computeIfAbsent(additionalDriverRow.serviceId(), serviceId -> new ArrayList<>())
                .add(serviceServiceMapper.toServiceAdditionalDriverSummary(additionalDriverRow));
        }
        return byService;
    }

    /**
     * Una fila que no tiene lo que el contrato promete se rechaza RUIDOSAMENTE, con el viaje
     * nombrado, en vez de publicarse con un null o desaparecer del reporte.
     *
     * <p>Las dos columnas son nullables en la base y el contrato las declara requeridas. Por la
     * aplicacion no hay camino —no se puede completar un viaje sin haberlo iniciado ni sin
     * conductor—, pero el cutover escribe por fuera de la aplicacion y la base no lo impide. En los
     * 905 viajes del sistema anterior hay 824 con fecha de fin y NINGUNO al que le falte el inicio ni el
     * conductor, asi que hoy esta guarda no muerde. El precio NO se chequea y no hace falta: su
     * columna es NOT NULL.
     *
     * <p>De las tres salidas posibles esta es la unica que no miente. Publicar el null rompe el
     * contrato que el cliente TypeScript ya genero. OMITIR la fila es peor todavia en un documento
     * de FACTURACION: los totales dejarian de cuadrar con la realidad y nadie se enteraria, que es
     * exactamente el modo de falla que no se puede detectar mirando el reporte. Fallar obliga a
     * mirar el dato, y el dato es el que esta mal. Mismo criterio que
     * {@code UserLookup.requireAllById} con las claves huerfanas.
     */
    private static void requireCompleteRow(ServiceReportRepository.ServicesReportRow reportRow) {
        if (reportRow.startDateTime() == null || reportRow.principalDriverName() == null) {
            // Se registra la PRESENCIA de cada dato, no su valor: para ubicar la fila alcanzan el id
            // y el codigo, y el nombre del conductor es dato personal de un trabajador en un log que
            // nadie rota con ese criterio.
            // ⚠️ Esta linea no la mide nadie: el caso afirma el 500, el codigo y el detalle, pero
            // ningun test captura el log. Es la unica linea del endpoint que existe solo para
            // diagnosticar, y borrarla deja la suite en verde.
            LOG.errorf("Viaje completado con datos incompletos para el reporte: id=%d code=%s "
                    + "tieneInicio=%s tieneConductor=%s",
                reportRow.serviceId(), reportRow.code(),
                reportRow.startDateTime() != null, reportRow.principalDriverName() != null);
            throw CommonError.INTERNAL_ERROR.toException(
                "El viaje " + reportRow.code() + " está completado pero le falta la fecha de inicio "
                    + "o el conductor, así que el reporte no puede emitirse. Reporte a soporte.");
        }
    }

    /**
     * Los totales, agrupados sobre las filas YA construidas y no con una consulta aparte.
     *
     * <p>Asi cuadran con lo que se publica POR CONSTRUCCION. Una segunda consulta puede derivar de
     * la primera en silencio —basta que alguien toque un filtro y no el otro— y un reporte cuyos
     * totales no suman sus propias filas es peor que no tener totales. El reporte de almacen suma
     * igual, sobre sus filas ya construidas (lo que NO comparte es la forma: alli son dos campos
     * fijos por moneda, aca una fila por moneda presente).
     *
     * <p>El orden lo fija la aparicion de cada moneda en las filas, que a su vez van por fecha: es
     * estable entre dos corridas de la misma semana, que es lo que un documento imprimible necesita.
     */
    private static List<ServicesReportTotalsResponse> toServicesReportTotals(
            List<ServicesReportRowResponse> rows) {
        Map<String, ServicesReportTotalsResponse> byCurrency = new LinkedHashMap<>();
        for (ServicesReportRowResponse row : rows) {
            byCurrency.merge(
                row.currencyCode(),
                new ServicesReportTotalsResponse(row.currencyCode(), 1, row.price()),
                (running, increment) -> new ServicesReportTotalsResponse(
                    running.currencyCode(),
                    running.totalServices() + increment.totalServices(),
                    running.totalRevenue().add(increment.totalRevenue())));
        }
        return List.copyOf(byCurrency.values());
    }
}
