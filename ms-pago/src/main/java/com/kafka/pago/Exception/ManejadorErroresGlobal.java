package com.kafka.pago.Exception;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Traduce excepciones a respuestas { error, detalle, timestamp } (§3.5).
 *
 * Cuidado deliberado con la tarjeta: el detalle de validación se arma SÓLO con
 * campo + mensaje, nunca con el valor rechazado, y el JSON ilegible no repite
 * el mensaje de Jackson. Así un número de tarjeta mal escrito no vuelve en la
 * respuesta ni termina en un log.
 */
@Slf4j
@RestControllerAdvice
public class ManejadorErroresGlobal {

    /** Mismo formato que AlertaFraudeDTO: LocalDateTime.toString() omite los segundos cuando son :00. */
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Value("${app.antifraude.bloqueo-minutos:30}")
    private int bloqueoMinutos;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        log.warn("Petición rechazada por validación: {}", detalle);
        return cuerpo(HttpStatus.BAD_REQUEST, "Datos inválidos", detalle);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> jsonIlegible(HttpMessageNotReadableException ex) {
        log.warn("Petición con JSON ilegible o valores no permitidos");
        return cuerpo(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "El cuerpo no es JSON válido o trae un valor no permitido (pasarela: WEBPAY|SIMULADA, origen: TIENDA|CONSOLA)");
    }

    /**
     * 429 con la forma que ya lee la consola ({ message }) más bloqueadoHasta e
     * intentos. El texto del message se conserva igual al anterior.
     */
    @ExceptionHandler(TarjetaBloqueadaException.class)
    public ResponseEntity<Map<String, Object>> bloqueada(TarjetaBloqueadaException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Demasiados intentos detectados. Estás bloqueado temporalmente por "
                + bloqueoMinutos + " minutos. Por favor, espera.");
        body.put("bloqueadoHasta", ex.getBloqueadoHasta().format(FORMATO_FECHA));
        body.put("intentos", ex.getIntentos());
        body.put("minutosRestantes", ex.getMinutosRestantes());
        body.put("error", "Bloqueado por antifraude");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(KafkaNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> kafkaCaido(KafkaNoDisponibleException ex) {
        return cuerpo(HttpStatus.SERVICE_UNAVAILABLE, "Servicio antifraude no disponible",
                "No se pudo registrar el intento de pago; reintente en unos segundos");
    }

    @ExceptionHandler(PasarelaNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> pasarelaCaida(PasarelaNoDisponibleException ex) {
        return cuerpo(HttpStatus.BAD_GATEWAY, "No se pudo conectar con la pasarela de pagos", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inesperado(Exception ex) {
        // Las excepciones estándar de Spring MVC (405 método, 415 content-type,
        // 404 ruta, 400 parámetro faltante) traen su propio código: sin esto el
        // handler genérico las convertía todas en 500.
        if (ex instanceof ErrorResponse respuesta) {
            HttpStatus estado = HttpStatus.valueOf(respuesta.getStatusCode().value());
            log.warn("Petición rechazada por Spring MVC: {} ({})", estado, ex.getClass().getSimpleName());
            return cuerpo(estado, estado.getReasonPhrase(), respuesta.getBody().getDetail());
        }
        log.error("Error no controlado: {}", ex.getClass().getSimpleName(), ex);
        return cuerpo(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "Revise los logs de ms-pago");
    }

    private static ResponseEntity<Map<String, Object>> cuerpo(HttpStatus estado, String error, String detalle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("detalle", detalle);
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(estado).body(body);
    }
}
