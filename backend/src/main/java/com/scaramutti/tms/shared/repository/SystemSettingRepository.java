package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.SystemSetting;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class SystemSettingRepository implements PanacheRepositoryBase<SystemSetting, String> {

    /** Todos los settings como mapa key->value (para armar el contexto del PDF de una sola query). */
    public Map<String, String> findAllAsMap() {
        return listAll().stream().collect(Collectors.toMap(setting -> setting.key, setting -> setting.value));
    }
}
