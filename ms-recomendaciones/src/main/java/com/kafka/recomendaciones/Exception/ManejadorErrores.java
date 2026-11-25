package com.kafka.recomendaciones.Exception;

import com.kafka.recomendaciones.DTO.ErrorRespuestaDTO;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Traduce excepciones a respuestas { error, detalle, timestamp } con el codigo
 * correcto. Un solo lugar evita que cada controller invente su propio formato
 * (la consola lee "error" para mostrar el mensaje).
 */
@Slf4j
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuestaDTO> cuerpoInvalido(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "Solicitud invalida", detalle);
    }

    /** Validacion de @RequestParam (p. ej. cantidad fuera de rango en /simular). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorRespuestaDTO> parametroInvalido(HandlerMethodValidationException ex) {
        String detalle = ex.getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "Parametro invalido", detalle);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorRespuestaDTO> restriccion(ConstraintViolationException ex) {
        return error(HttpStatus.BAD_REQUEST, "Parametro invalido", ex.getMessage());
    }

    @ExceptionHandler(PayloadInvalidoException.class)
    public ResponseEntity<ErrorRespuestaDTO> payloadInvalido(PayloadInvalidoException ex) {
        return error(HttpStatus.BAD_REQUEST, "Solicitud invalida", ex.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorRespuestaDTO> parametroFaltante(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "Parametro invalido", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorRespuestaDTO> jsonIlegible(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Cuerpo ilegible", "El cuerpo no es JSON valido para esta operacion");
    }

    /** Lo inesperado se loguea completo pero no se expone el stack trace al cliente. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuestaDTO> inesperado(Exception ex) {
        log.error("Error no controlado en la API: {}", ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "Revise los logs de ms-recomendaciones");
    }

    private ResponseEntity<ErrorRespuestaDTO> error(HttpStatus estado, String error, String detalle) {
        return ResponseEntity.status(estado).body(ErrorRespuestaDTO.de(error, detalle));
    }
}
