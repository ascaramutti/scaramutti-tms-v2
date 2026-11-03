package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.User_;
import com.scaramutti.tms.shared.repository.WorkerRepository.UserRefRow;
import com.scaramutti.tms.shared.util.DateUtils;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Las cuentas de {@code public.users}. Vive en {@code shared} porque las leen y escriben varios
 * modulos: auth (la sesion y los autores que embeben las demas respuestas), trabajadores (en sus
 * escrituras, la cuenta de quien escribe y la del trabajador que cambia) y usuarios (su lista y su
 * detalle).
 */
@ApplicationScoped
public class UserRepository implements PanacheRepositoryBase<User, Integer> {

    /**
     * Busca un usuario por username. Devuelve Optional vacio si no existe.
     * Usa singleResultOptional() para detectar bugs/corrupcion de data:
     * si por alguna razon hubiera dos usuarios con el mismo username,
     * lanza NonUniqueResultException en vez de silenciosamente tomar el primero.
     */
    public Optional<User> findByUsername(String username) {
        return find(User_.USERNAME, username).singleResultOptional();
    }

    /** El usuario de un trabajador, o vacio. Hay a lo sumo uno: la columna es unica. */
    public Optional<User> findByWorkerIdOptional(Integer workerId) {
        return find("worker.id", workerId).singleResultOptional();
    }

    /**
     * Si el trabajador tiene usuario, sin cargarlo. NO filtra por vigente a proposito: quien
     * pregunta decide si un cambio de cargo dejaria una cuenta con un rol sin permisos, y una
     * cuenta apagada se puede volver a encender.
     */
    public boolean existsByWorkerId(Integer workerId) {
        return count("worker.id", workerId) > 0;
    }

    /**
     * El id del trabajador de una cuenta, como valor suelto y SIN cargar la cuenta. Quien lo pide
     * va a bloquear esa fila y recien despues leer la cuenta: si la cuenta ya estuviera en memoria,
     * esa lectura devolveria la copia de antes de esperar y no la confirmada.
     */
    public Optional<Integer> findWorkerIdByUserId(Integer userId) {
        return getEntityManager()
            .createQuery("SELECT u.worker.id FROM User u WHERE u.id = :userId", Integer.class)
            .setParameter("userId", userId).getResultStream().findFirst();
    }

    /**
     * El hash de la cuenta como valor suelto y SIN cargarla: el cambio de clave lo verifica antes de
     * tomar la fila, y con la cuenta ya en memoria la toma de despues falla (la guarda de lo cargado).
     */
    public Optional<String> findPasswordHashByUserId(Integer userId) {
        return getEntityManager()
            .createQuery("SELECT u.passwordHash FROM User u WHERE u.id = :userId", String.class)
            .setParameter("userId", userId).getResultStream().findFirst();
    }

    /**
     * Si la cuenta esta hoy habilitada para escribir con alguno de esos roles, como valor suelto y
     * SIN cargarla, por el mismo motivo que el metodo de arriba: se pregunta ANTES de bloquear, y
     * una cuenta en memoria haria que la validacion de despues leyera la copia vieja.
     */
    public boolean isEnabledToWrite(Integer userId, Collection<String> roleNames) {
        return getEntityManager().createQuery(
                "SELECT count(u) FROM User u WHERE u.id = :userId AND u.isActive = true "
                    + "AND u.worker.role.name IN :roleNames AND u.worker.isActive = true", Long.class)
            .setParameter("userId", userId).setParameter("roleNames", roleNames)
            .getSingleResult() > 0;
    }

    /**
     * Las cuentas con su trabajador y su cargo, en una consulta que no lee el hash ni la version de
     * las sesiones. El nombre completo sale de {@link WorkerRepository#fullNameExpression}, la misma
     * composicion que el detalle del trabajador. {@code isActive} nulo = todas.
     */
    public List<UserAccountRow> listAccounts(Boolean isActive) {
        String sql = "SELECT u.id AS u_id, u.username AS u_username, u.is_active AS u_is_active, "
            + "w.id AS w_id, " + WorkerRepository.fullNameExpression("w") + " AS w_full_name, "
            + "w.is_active AS w_is_active, "
            + "r.name AS role_name, r.description AS role_description, r.level AS role_level, "
            + "r.can_login AS role_can_login, r.driver_profile AS role_driver_profile "
            + "FROM public.users u "
            + "JOIN public.workers w ON w.id = u.worker_id "
            + "JOIN public.roles r ON r.id = w.role_id "
            + (isActive == null ? "" : "WHERE u.is_active = :isActive ")
            + "ORDER BY u.username, u.id";

        Query query = getEntityManager().createNativeQuery(sql, Tuple.class);
        if (isActive != null) {
            query.setParameter("isActive", isActive);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(UserRepository::toUserAccountRow).toList();
    }

    private static UserAccountRow toUserAccountRow(Tuple row) {
        return new UserAccountRow(
            intOf(row, "u_id"),
            (String) row.get("u_username"),
            new UserAccountWorkerRow(
                intOf(row, "w_id"),
                (String) row.get("w_full_name"),
                (Boolean) row.get("w_is_active")),
            new UserAccountRoleRow(
                (String) row.get("role_name"),
                (String) row.get("role_description"),
                intOf(row, "role_level"),
                (Boolean) row.get("role_can_login"),
                (String) row.get("role_driver_profile")),
            (Boolean) row.get("u_is_active"));
    }

    /**
     * Una cuenta con su trabajador, su cargo y quien la creo y la modifico, en una consulta que no lee
     * el hash ni la version de las sesiones. Las columnas se leen por alias, como en el detalle del
     * trabajador. Toda union es por clave primaria o por una columna unica: ninguna multiplica la fila,
     * y por eso basta la primera.
     */
    public Optional<UserAccountDetailRow> findDetailById(Integer userId) {
        String sql = "SELECT u.id AS u_id, u.username AS u_username, u.is_active AS u_is_active, "
            + "u.created_at AS u_created_at, u.updated_at AS u_updated_at, "
            + "w.id AS w_id, " + WorkerRepository.fullNameExpression("w") + " AS w_full_name, "
            + "w.is_active AS w_is_active, " + WorkerRepository.namePartsColumns("w", "w") + ", "
            + "r.name AS role_name, r.description AS role_description, r.level AS role_level, "
            + "r.can_login AS role_can_login, r.driver_profile AS role_driver_profile, "
            + "cu.id AS created_by_id, cu.username AS created_by_username, "
            + WorkerRepository.fullNameExpression("cw") + " AS created_by_full_name, "
            + "cr.description AS created_by_position, "
            + "uu.id AS updated_by_id, uu.username AS updated_by_username, "
            + WorkerRepository.fullNameExpression("uw") + " AS updated_by_full_name, "
            + "ur.description AS updated_by_position "
            + "FROM public.users u "
            + "JOIN public.workers w ON w.id = u.worker_id "
            + "JOIN public.roles r ON r.id = w.role_id "
            + "LEFT JOIN public.users cu ON cu.id = u.created_by "
            + "LEFT JOIN public.workers cw ON cw.id = cu.worker_id "
            + "LEFT JOIN public.roles cr ON cr.id = cw.role_id "
            + "LEFT JOIN public.users uu ON uu.id = u.updated_by "
            + "LEFT JOIN public.workers uw ON uw.id = uu.worker_id "
            + "LEFT JOIN public.roles ur ON ur.id = uw.role_id "
            + "WHERE u.id = :userId";

        Query query = getEntityManager().createNativeQuery(sql, Tuple.class);
        query.setParameter("userId", userId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        return rows.stream().findFirst().map(UserRepository::toUserAccountDetailRow);
    }

    private static UserAccountDetailRow toUserAccountDetailRow(Tuple row) {
        return new UserAccountDetailRow(
            intOf(row, "u_id"),
            (String) row.get("u_username"),
            new UserAccountWorkerDetailRow(
                intOf(row, "w_id"),
                (String) row.get("w_full_name"),
                (Boolean) row.get("w_is_active"),
                (String) row.get("w_first_name"),
                (String) row.get("w_paternal_last_name"),
                (String) row.get("w_maternal_last_name")),
            new UserAccountRoleRow(
                (String) row.get("role_name"),
                (String) row.get("role_description"),
                intOf(row, "role_level"),
                (Boolean) row.get("role_can_login"),
                (String) row.get("role_driver_profile")),
            (Boolean) row.get("u_is_active"),
            DateUtils.toOffsetDateTime(row.get("u_created_at")),
            userRefOf(row, "created_by"),
            DateUtils.toOffsetDateTime(row.get("u_updated_at")),
            userRefOf(row, "updated_by"));
    }

    /**
     * Quien creo o modifico, o nulo. Es la regla del detalle del trabajador: el nulo se decide por el
     * id y no por el nombre, porque preguntar por el campo equivocado daria una referencia a medias en
     * vez de ninguna.
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

    /** Postgres devuelve los enteros con anchos distintos segun la columna; se normalizan aca. */
    private static Integer intOf(Tuple row, String alias) {
        Object value = row.get(alias);
        return value == null ? null : ((Number) value).intValue();
    }

    /** Una fila de la lista, tal como sale de la consulta. */
    public record UserAccountRow(
        Integer id, String username, UserAccountWorkerRow worker, UserAccountRoleRow role, Boolean isActive) {}

    public record UserAccountWorkerRow(Integer id, String fullName, Boolean isActive) {}

    /** Una cuenta del detalle, tal como sale de la consulta. Los autores son nulos sin firma. */
    public record UserAccountDetailRow(
        Integer id, String username, UserAccountWorkerDetailRow worker, UserAccountRoleRow role,
        Boolean isActive, OffsetDateTime createdAt, UserRefRow createdBy, OffsetDateTime updatedAt,
        UserRefRow updatedBy) {}

    /** {@code maternalLastName} es nulo sin materno: la columna no admite un texto vacio ni de solo espacios. */
    public record UserAccountWorkerDetailRow(
        Integer id, String fullName, Boolean isActive, String firstName, String paternalLastName,
        String maternalLastName) {}

    /** {@code driverProfile} es el texto crudo de la columna, que la respuesta lleva tal cual. */
    public record UserAccountRoleRow(
        String name, String description, Integer level, Boolean canLogin, String driverProfile) {}
}
