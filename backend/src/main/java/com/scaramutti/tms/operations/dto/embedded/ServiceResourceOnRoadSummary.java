package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Cuantos recursos de un tipo estan en ruta AHORA, sobre el padron de ese tipo.
 *
 * <p>Los dos numeros exigen que el recurso este de ALTA. El sistema anterior lo pedia solo en el
 * denominador, asi que un conductor dado de baja mientras su viaje seguia en ruta producia un
 * "6 de 5": una fraccion imposible, que se lee como sistema roto. La paridad que importa es la del
 * SIGNIFICADO del numero, no la del defecto.
 */
public record ServiceResourceOnRoadSummary(

    @Schema(description = "Los que están en ruta ahora")
    int active,

    @Schema(description = "El padrón vigente: los que están de alta. Ni este número ni active miran la disponibilidad del catálogo")
    int total
) {}
