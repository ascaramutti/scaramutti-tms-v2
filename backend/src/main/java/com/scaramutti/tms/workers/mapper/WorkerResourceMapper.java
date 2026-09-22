package com.scaramutti.tms.workers.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.workers.dto.WorkerDriverProfileRequest;
import com.scaramutti.tms.workers.dto.WorkerRequest;
import com.scaramutti.tms.workers.service.cmd.CreateWorkerCommand;
import com.scaramutti.tms.workers.service.cmd.ListWorkersQuery;
import com.scaramutti.tms.workers.service.cmd.WorkerDriverProfileCommand;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST del listado de trabajadores. {@code q} se normaliza con
 * {@code trimToNull} (mismo criterio que products/suppliers): un {@code q} en blanco no
 * filtra. {@code isActive} pasa tal cual.
 *
 * <p>{@code RETURN_DEFAULT} es obligatorio: ambos params son opcionales, y sin filtros
 * (GET /workers pelado) los dos llegan null; sin esta estrategia MapStruct devolveria el
 * Query null y el service reventaria (gotcha conocido del proyecto con params opcionales).
 */
@Mapper(
    config = SharedMapperConfig.class,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WorkerResourceMapper {

    @Mapping(target = "q", source = "q", qualifiedByName = "trimToNull")
    ListWorkersQuery toListWorkersQuery(String q, Boolean isActive);

    /**
     * El cuerpo del alta al command. Recorta los TRES textos que el contrato declara
     * normalizados, y no les cambia la caja: el nombre de una persona se guarda como lo
     * escribieron.
     *
     * <p>El telefono NO se recorta, porque su formato ya exige nueve digitos exactos y
     * cualquier espacio lo rechaza el borde. El cargo tampoco: es el nombre de sistema de una
     * fila del catalogo, asi que uno con espacios no existe, y eso ya es el error de cargo
     * invalido. Recortarlos seria arreglar en silencio un cuerpo que el contrato no promete
     * arreglar.
     *
     * <p>El recorte ocurre DESPUES de la validacion del borde, asi que un texto de largo
     * maximo con espacios alrededor se rechaza aunque recortado entre. Es el mismo
     * comportamiento del modulo de clientes y esta medido como caso propio.
     */
    @Mapping(target = "firstName",      source = "firstName",      qualifiedByName = "trimToNull")
    @Mapping(target = "lastName",       source = "lastName",       qualifiedByName = "trimToNull")
    @Mapping(target = "documentNumber", source = "documentNumber", qualifiedByName = "trimToNull")
    CreateWorkerCommand toCreateWorkerCommand(WorkerRequest workerRequest);

    /**
     * La ficha del cuerpo a su command, y se declara a mano SOLO por la estrategia de nulos.
     *
     * <p>Este mapper devuelve objetos vacios en vez de nulos, porque el listado lo necesita
     * para sus dos parametros opcionales, y esa estrategia alcanza tambien a los mapeos
     * anidados que el generador deduce solo. Sin esta declaracion, un alta SIN ficha llegaria
     * al servicio con una ficha de campos vacios, y entonces un cargo que no lleva ficha se
     * rechazaria como si la hubieran mandado, y uno que la exige pasaria sin ella: la regla
     * quedaria invertida en los dos sentidos sin que nada dejara de compilar.
     */
    @BeanMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_NULL)
    @Mapping(target = "licenseNumber",   source = "licenseNumber",   qualifiedByName = "trimToNull")
    @Mapping(target = "licenseCategory", source = "licenseCategory", qualifiedByName = "trimToNull")
    WorkerDriverProfileCommand toWorkerDriverProfileCommand(
        WorkerDriverProfileRequest workerDriverProfileRequest);
}
