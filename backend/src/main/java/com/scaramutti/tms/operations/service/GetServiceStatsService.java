package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.ServiceStatsResponse;
import com.scaramutti.tms.operations.dto.embedded.ServiceResourceOnRoadSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceWeekCycleSummary;
import com.scaramutti.tms.shared.repository.ServiceStatsRepository;
import com.scaramutti.tms.shared.repository.ServiceStatsRepository.ServiceStatsRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Los indicadores del tablero operativo.
 *
 * <p>Sin {@code @Transactional}, igual que el strip de almacen: es una sola consulta de lectura y
 * abrir una transaccion para ella no compra nada.
 *
 * <p>El ciclo se calcula ACA y se le pasa a la consulta ya resuelto en instantes. Es deliberado: si
 * la base tuviera que derivar la semana, la regla del negocio quedaria escrita en SQL y en hora del
 * SERVIDOR, que es exactamente como se equivoca el tablero del sistema anterior.
 */
@ApplicationScoped
public class GetServiceStatsService {

    @Inject ServiceStatsRepository serviceStatsRepository;

    public ServiceStatsResponse getServiceStats() {
        ServiceWeekCycleSummary weekCycle = OperationsWeekCycle.current();
        ServiceStatsRow row = serviceStatsRepository.getStats(
            OperationsWeekCycle.startInstant(weekCycle),
            OperationsWeekCycle.endExclusiveInstant(weekCycle));

        // Se arma a mano y no por MapStruct: los dos pares salen de CUATRO columnas planas de una
        // proyeccion, y cruzarlos —el total de conductores con los tractos en ruta— es el error que
        // el mapeo declarativo no puede evitar y que un lector si ve en estas cuatro lineas.
        return new ServiceStatsResponse(
            row.pendingAssignment(),
            row.pendingStart(),
            row.inProgress(),
            row.completedThisWeek(),
            new ServiceResourceOnRoadSummary(row.principalDriversOnRoad(), row.activeDriversTotal()),
            new ServiceResourceOnRoadSummary(row.principalTractorsOnRoad(), row.activeTractorsTotal()),
            weekCycle);
    }
}
