package com.scaramutti.tms.operations.service.cmd;

import java.time.LocalDate;

/**
 * La semana que se pide, identificada por el MIERCOLES que la abre, en fecha de Lima.
 *
 * <p>Se identifica por fecha ABSOLUTA y no por un desplazamiento relativo ("la semana pasada"),
 * que es como lo pide el sistema anterior: un parametro cuyo significado cambia con el paso del
 * tiempo convierte un enlace guardado —o una pestana abierta desde ayer— en otra semana sin que
 * nadie toque nada. La fecha absoluta ademas es la que el tablero de indicadores ya publica, asi
 * que la pantalla navega sumando o restando siete dias.
 */
public record GetServicesReportQuery(LocalDate weekStart) {
}
