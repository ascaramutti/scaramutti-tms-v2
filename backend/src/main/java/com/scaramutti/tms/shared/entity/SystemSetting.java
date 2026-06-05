package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Configuracion key-value editable del sistema (`cotizaciones.system_settings`).
 * Para datos que cambian sin redeploy: empresa emisora, terminos y condiciones y
 * cuentas bancarias del PDF de cotizacion. Seed manual con db/seed_system_settings.sql
 * (dev y prod); el DevDataSeeder no lo siembra.
 */
@Entity
@Table(name = "system_settings", schema = "cotizaciones")
public class SystemSetting {

    @Id
    @Column(name = "key", length = 100)
    public String key;

    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    public String value;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
