package com.kafka.pago.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Calcula la huella con la que el antifraude identifica "la misma tarjeta"
 * sin guardar la tarjeta: SHA-256(numeroNormalizado + pepper) en hex.
 *
 * Por qué un hash y no el número: la regla sólo necesita saber si dos intentos
 * son de la misma tarjeta (igualdad), nunca cuál es. Guardar el PAN pondría a
 * la base bajo PCI DSS; guardar la huella no expone nada útil.
 */
@Slf4j
@Component
public class HuellaTarjeta {

    /** Prefijo de la huella de cuenta: evita que coincida con la de algún número. */
    static final String PREFIJO_CUENTA = "cuenta:";

    private final String pepper;

    public HuellaTarjeta(@Value("${app.antifraude.pepper:pedidos360-dev}") String pepper) {
        if ("pedidos360-dev".equals(pepper)) {
            log.warn("ANTIFRAUDE_PEPPER usa el valor de desarrollo: definir uno propio fuera del entorno local");
        }
        this.pepper = pepper;
    }

    /** Sólo dígitos: "4051 8856-0044 6623" y "4051885600446623" son la misma tarjeta. */
    public static String normalizar(String numero) {
        return numero == null ? "" : numero.replaceAll("[^0-9]", "");
    }

    public static String ultimos4(String numero) {
        String digitos = normalizar(numero);
        return digitos.length() < 4 ? null : digitos.substring(digitos.length() - 4);
    }

    /** Primeros 12 hex: suficiente para distinguir en logs y pantalla sin exponer la huella entera. */
    public static String abreviar(String huella) {
        return huella == null ? null : huella.substring(0, Math.min(12, huella.length()));
    }

    public String deTarjeta(String numero) {
        String digitos = normalizar(numero);
        if (digitos.isEmpty()) {
            throw new IllegalArgumentException("No hay dígitos de tarjeta para calcular la huella");
        }
        return sha256(digitos + pepper);
    }

    /**
     * Huella para WEBPAY. Transbank captura la tarjeta en SU formulario y no la
     * entrega antes del pago (diseño PCI DSS): en el momento de iniciar no hay
     * número que hashear. Se usa entonces la cuenta del cliente como clave; la
     * ventana y el umbral son exactamente los mismos, sólo cambia qué se cuenta.
     */
    public String deCuenta(String idCliente) {
        if (idCliente == null || idCliente.isBlank()) {
            throw new IllegalArgumentException("idCliente vacío");
        }
        return sha256(PREFIJO_CUENTA + idCliente.trim() + pepper);
    }

    private static String sha256(String texto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM está obligada a traer SHA-256: si falta, la plataforma está rota.
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}
