package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Tipo de documento de identidad de {@code public.document_types}. De solo lectura: se
 * agrega uno por migracion, no desde la aplicacion.
 *
 * <p>{@code maxLength} y {@code validationPattern} no son adorno: son las reglas que la
 * escritura aplica al numero de documento, y por eso viajan tambien al cliente.
 */
@Entity
@Table(name = "document_types")
public class DocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(nullable = false, unique = true, length = 10)
    public String code;

    @Column(nullable = false, length = 50)
    public String name;

    public String description;

    @Column(name = "max_length", nullable = false)
    public Integer maxLength;

    /** Expresion regular completa que el numero debe cumplir; nula si el tipo no define una. */
    @Column(name = "validation_pattern", length = 255)
    public String validationPattern;

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
