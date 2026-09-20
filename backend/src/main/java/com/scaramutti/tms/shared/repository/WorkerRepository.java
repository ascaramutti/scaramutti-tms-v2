package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.shared.util.MultiWordSearch;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class WorkerRepository implements PanacheRepositoryBase<Worker, Integer> {

    /**
     * El nombre completo de un trabajador, armado en SQL con el mismo criterio que
     * {@link Worker#fullName()}: nombre, espacio, apellido.
     *
     * <p>Vive aca y no en otro repositorio porque el nombre es del trabajador, y existe
     * como metodo y no como literal repetido porque lo componen el listado de conductores,
     * la asignacion de recursos, los informes de viajes y el detalle de un trabajador: dos
     * formas de armarlo harian que la misma persona se llame distinto segun por donde se la
     * mire.
     *
     * <p>El alias es parametro porque una misma consulta puede necesitarlo mas de una vez
     * sobre filas distintas de {@code public.workers}: el detalle lo compone para quien creo
     * y para quien modifico en la misma sentencia.
     */
    public static String fullNameExpression(String workersAlias) {
        return "trim(" + workersAlias + ".first_name || ' ' || " + workersAlias + ".last_name)";
    }

    /**
     * Listado (sin paginar) de {@code public.workers} para el combobox "quien recibe" del
     * retiro (GET /workers). {@code q} es multi-palabra (RN-WH14, molde suppliers/products):
     * cada palabra debe matchear en {@code first_name} O {@code last_name}; nulo = sin filtro.
     * {@code isActive} nulo = ambos. Orden natural {@code first_name, last_name} ASC (el
     * frontend reordena para presentacion, politica de catalogos). Query nativa para poder
     * usar {@link MultiWordSearch}; devuelve entidades gestionadas.
     */
    public List<Worker> search(String q, Boolean isActive) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>();
        if (q != null) {
            conditions.addAll(MultiWordSearch.conditions(q, List.of("first_name", "last_name"), "qtok", params));
        }
        if (isActive != null) {
            conditions.add("is_active = :isActive");
            params.put("isActive", isActive);
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + " ";
        String sql = "SELECT * FROM public.workers " + where + "ORDER BY first_name ASC, last_name ASC";

        Query query = getEntityManager().createNativeQuery(sql, Worker.class);
        params.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Worker> result = query.getResultList();
        return result;
    }

    /**
     * El detalle de un trabajador, en UNA consulta.
     *
     * <p>Por navegacion de entidades serian entre siete y nueve, y el numero lo decidiria
     * Hibernate y no este codigo: un usuario apunta a su trabajador y el trabajador de vuelta
     * al usuario que lo creo, y ese ciclo obliga a cortar y emitir sentencias sueltas. Con la
     * proyeccion, once uniones sobre tablas de decenas de filas, todas por clave primaria o
     * por clave unica, y ninguna multiplica: el usuario de un trabajador y su ficha de
     * conductor son unicos por sus restricciones.
     *
     * <p>Ni el rol ni el tipo de documento se filtran por activo, a proposito: un trabajador
     * conserva y muestra un cargo o un tipo retirados. El que filtra por activo es el
     * catalogo, que ofrece lo que se puede elegir hoy; el detalle cuenta lo que hay.
     *
     * <p>{@code hasUser} es verdadero tambien con el usuario inactivo: desactivar un
     * trabajador apaga la cuenta y no la borra, y la pantalla necesita saber que sigue ahi.
     *
     * <p>Las columnas se leen por ALIAS y no por posicion. Son treinta y tres: leerlas por
     * numero es el modo de falla obvio el dia que alguien agregue una en el medio.
     */
    public Optional<WorkerDetailRow> findDetailById(Integer workerId) {
        String sql = "SELECT w.id AS w_id, w.first_name AS w_first_name, w.last_name AS w_last_name, "
            + "w.document_number AS w_document_number, w.phone AS w_phone, w.hire_date AS w_hire_date, "
            + "w.is_active AS w_is_active, w.created_at AS w_created_at, w.updated_at AS w_updated_at, "
            + "dt.id AS dt_id, dt.code AS dt_code, dt.name AS dt_name, "
            + "dt.max_length AS dt_max_length, dt.validation_pattern AS dt_validation_pattern, "
            + "r.name AS role_name, r.description AS role_description, r.level AS role_level, "
            + "r.can_login AS role_can_login, r.driver_profile AS role_driver_profile, "
            + "d.id AS driver_id, d.license_number AS driver_license_number, "
            + "d.category AS driver_license_category, ds.name AS driver_status_name, "
            + "d.is_active AS driver_is_active, "
            + "cu.id AS created_by_id, cu.username AS created_by_username, "
            + fullNameExpression("cw") + " AS created_by_full_name, cr.description AS created_by_position, "
            + "uu.id AS updated_by_id, uu.username AS updated_by_username, "
            + fullNameExpression("uw") + " AS updated_by_full_name, ur.description AS updated_by_position, "
            + "(hu.id IS NOT NULL) AS has_user "
            + "FROM public.workers w "
            + "JOIN public.document_types dt ON dt.id = w.document_type_id "
            + "JOIN public.roles r ON r.id = w.role_id "
            + "LEFT JOIN public.drivers d ON d.worker_id = w.id "
            + "LEFT JOIN public.resource_statuses ds ON ds.id = d.status_id "
            + "LEFT JOIN public.users cu ON cu.id = w.created_by "
            + "LEFT JOIN public.workers cw ON cw.id = cu.worker_id "
            + "LEFT JOIN public.roles cr ON cr.id = cw.role_id "
            + "LEFT JOIN public.users uu ON uu.id = w.updated_by "
            + "LEFT JOIN public.workers uw ON uw.id = uu.worker_id "
            + "LEFT JOIN public.roles ur ON ur.id = uw.role_id "
            + "LEFT JOIN public.users hu ON hu.worker_id = w.id "
            + "WHERE w.id = :workerId";

        Query query = getEntityManager().createNativeQuery(sql, Tuple.class);
        query.setParameter("workerId", workerId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().findFirst().map(WorkerRepository::toWorkerDetailRow);
    }

    private static WorkerDetailRow toWorkerDetailRow(Tuple row) {
        return new WorkerDetailRow(
            intOf(row, "w_id"),
            (String) row.get("w_first_name"),
            (String) row.get("w_last_name"),
            new DocumentTypeRow(
                intOf(row, "dt_id"),
                (String) row.get("dt_code"),
                (String) row.get("dt_name"),
                intOf(row, "dt_max_length"),
                (String) row.get("dt_validation_pattern")),
            (String) row.get("w_document_number"),
            (String) row.get("w_phone"),
            new RoleRow(
                (String) row.get("role_name"),
                (String) row.get("role_description"),
                intOf(row, "role_level"),
                (Boolean) row.get("role_can_login"),
                (String) row.get("role_driver_profile")),
            DateUtils.toLocalDate(row.get("w_hire_date")),
            (Boolean) row.get("w_is_active"),
            DateUtils.toOffsetDateTime(row.get("w_created_at")),
            userRefOf(row, "created_by"),
            DateUtils.toOffsetDateTime(row.get("w_updated_at")),
            userRefOf(row, "updated_by"),
            driverProfileOf(row),
            (Boolean) row.get("has_user"));
    }

    /**
     * Quien creo o modifico, o nulo. El nulo se decide por el id y no por el nombre: un
     * trabajador sin nombre no existe, pero preguntar por el campo equivocado daria una
     * referencia a medias en vez de ninguna.
     */
    private static UserRefRow userRefOf(Tuple row, String prefix) {
        Integer userId = intOf(row, prefix + "_id");
        if (userId == null) {
            return null;
        }
        return new UserRefRow(
            userId,
            (String) row.get(prefix + "_username"),
            (String) row.get(prefix + "_full_name"),
            (String) row.get(prefix + "_position"));
    }

    private static DriverProfileRow driverProfileOf(Tuple row) {
        Integer driverId = intOf(row, "driver_id");
        if (driverId == null) {
            return null;
        }
        return new DriverProfileRow(
            driverId,
            (String) row.get("driver_license_number"),
            (String) row.get("driver_license_category"),
            (String) row.get("driver_status_name"),
            (Boolean) row.get("driver_is_active"));
    }

    /** Postgres devuelve los enteros con anchos distintos segun la columna; se normalizan aca. */
    private static Integer intOf(Tuple row, String alias) {
        Object value = row.get(alias);
        return value == null ? null : ((Number) value).intValue();
    }

    /** El detalle completo, tal como sale de la consulta. */
    public record WorkerDetailRow(
        Integer id,
        String firstName,
        String lastName,
        DocumentTypeRow documentType,
        String documentNumber,
        String phone,
        RoleRow role,
        LocalDate hireDate,
        Boolean isActive,
        OffsetDateTime createdAt,
        UserRefRow createdBy,
        OffsetDateTime updatedAt,
        UserRefRow updatedBy,
        DriverProfileRow driver,
        Boolean hasUser
    ) {}

    public record DocumentTypeRow(
        Integer id, String code, String name, Integer maxLength, String validationPattern) {}

    /** {@code driverProfile} es el texto crudo de la columna; lo traduce el mapper. */
    public record RoleRow(
        String name, String description, Integer level, Boolean canLogin, String driverProfile) {}

    public record UserRefRow(Integer id, String username, String fullName, String position) {}

    /** {@code statusName} es el nombre crudo del catalogo; lo traduce el mapper. */
    public record DriverProfileRow(
        Integer id, String licenseNumber, String licenseCategory, String statusName, Boolean isActive) {}
}
