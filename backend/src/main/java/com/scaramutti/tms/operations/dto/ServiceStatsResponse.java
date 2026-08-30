package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.dto.embedded.ServiceResourceOnRoadSummary;
import com.scaramutti.tms.operations.dto.embedded.ServiceWeekCycleSummary;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * La tira de indicadores que va arriba del listado de viajes.
 *
 * <p>El cuerpo es IDENTICO para los cinco roles que lo consultan: no lleva un solo importe, asi que
 * la regla que le esconde los precios al despacho no recorta nada aca. Se deja escrito para que
 * nadie copie esa maquinaria a un endpoint que no la necesita.
 *
 * <p>Los estados CANCELADO y ELIMINADO no entran en ningun contador.
 */
public record ServiceStatsResponse(

    @Schema(description = "Viajes esperando que se les asignen recursos. GLOBAL: sin ventana de tiempo, el ciclo semanal solo aplica a completedThisWeek")
    int pendingAssignment,

    @Schema(description = "Viajes con recursos asignados que todavía no arrancaron. GLOBAL: sin ventana de tiempo, el ciclo semanal solo aplica a completedThisWeek")
    int pendingStart,

    @Schema(description = "Viajes en ruta. GLOBAL: sin ventana de tiempo, el ciclo semanal solo aplica a completedThisWeek")
    int inProgress,

    @Schema(description = "Completados con fecha de fin real dentro del ciclo operativo. Uno sin esa fecha no cae en ninguna semana")
    int completedThisWeek,

    @Schema(description = "Conductores PRINCIPALES distintos en ruta; los refuerzos no cuentan")
    ServiceResourceOnRoadSummary driversOnRoad,

    @Schema(description = "TRACTOS principales distintos en ruta. Carretas y escoltas NO participan, pese al nombre del campo")
    ServiceResourceOnRoadSummary unitsOnRoad,

    ServiceWeekCycleSummary weekCycle
) {}
