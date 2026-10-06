package com.scaramutti.tms.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Rol de {@code public.roles}. Desde el mantenimiento de trabajadores es tambien el
 * CARGO: una sola jerarquia en vez de dos, y por eso {@code description} paso a ser el
 * nombre visible del cargo (lo que muestran el pie del menu, la firma de auditoria y el
 * PDF de cotizacion) y no un texto libre.
 *
 * <p>La tabla tiene once filas: las siete que inician sesion y cuatro que nunca lo hacen
 * (conductor, escolta, ayudante, operador). Agregar un rol o cambiarle el nivel es una
 * migracion, no una pantalla.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    /** Nombre de sistema, en ingles: es el identificador, no el texto que se muestra. */
    @Column(nullable = false, unique = true)
    public String name;

    /** Nombre visible del cargo. */
    public String description;

    /**
     * Nivel del organigrama, de 4 a 1. Sin valor por omision en la columna a proposito:
     * todo rol nuevo nace con su nivel, asi que una fila que lo omita falla al insertarse
     * en vez de entrar con un nivel inventado.
     */
    @Column(nullable = false)
    public Short level;

    /**
     * Si el rol puede tener usuario. NO se deduce del nivel: el encargado de almacen es de
     * nivel 1 y si inicia sesion, mientras que conductor, escolta, ayudante y operador, del
     * mismo nivel, no. Son dos cosas distintas y por eso son dos columnas.
     */
    @Column(name = "can_login", nullable = false)
    public Boolean canLogin = true;

    /**
     * Modalidad de ficha de conductor del rol, como texto. El dominio lo cierra el CHECK de
     * la columna; el modulo de trabajadores lo convierte a su enum al leerlo.
     *
     * <p>Es texto y no un enum mapeado para que esta entidad, que vive en el paquete
     * compartido, no importe un enum de un modulo. Mismo criterio que la bitacora de
     * almacen con su tipo de cambio.
     */
    @Column(name = "driver_profile", nullable = false)
    public String driverProfile = "NONE";

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;
}
