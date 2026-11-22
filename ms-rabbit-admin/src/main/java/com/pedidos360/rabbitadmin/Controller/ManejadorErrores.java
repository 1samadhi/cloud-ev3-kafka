package com.pedidos360.rabbitadmin.Controller;

import com.pedidos360.rabbitadmin.DTO.ErrorRespuestaDTO;
import com.pedidos360.rabbitadmin.Exception.ConflictoException;
import com.pedidos360.rabbitadmin.Exception.ManagementNoDisponibleException;
import com.pedidos360.rabbitadmin.Exception.RecursoNoEncontradoException;
import com.pedidos360.rabbitadmin.Exception.RecursoProtegidoException;
import com.pedidos360.rabbitadmin.Exception.RecursoYaExisteException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;
import java.util.stream.Collectors;

/** Errores HTTP con la forma { error, detalle, timestamp } (§3.5). */
@Slf4j
@RestControllerAdvice
public class ManejadorErrores {

    // ------------------------------------------------------------------- 400

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuestaDTO> cuerpoInvalido(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getAllErrors().stream()
                .map(err -> err instanceof FieldError fe
                        ? fe.getField() + ": " + fe.getDefaultMessage()
                        : err.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ErrorRespuestaDTO.de("Petición inválida", detalle));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorRespuestaDTO> parametroInvalido(HandlerMethodValidationException e) {
        String detalle = e.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ErrorRespuestaDTO.de("Petición inválida", detalle));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorRespuestaDTO> peticionMalFormada(Exception e) {
        String detalle = e instanceof HttpMessageNotReadableException
                ? "El cuerpo no es un JSON válido o no tiene la forma esperada"
                : e.getMessage();
        return ResponseEntity.badRequest().body(ErrorRespuestaDTO.de("Petición inválida", detalle));
    }

    // ------------------------------------------------------- 403 / 404 / 409

    @ExceptionHandler(RecursoProtegidoException.class)
    public ResponseEntity<ErrorRespuestaDTO> protegido(RecursoProtegidoException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorRespuestaDTO.de("Recurso protegido", e.getMessage()));
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorRespuestaDTO> noEncontrado(RecursoNoEncontradoException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorRespuestaDTO.de("No encontrado", e.getMessage()));
    }

    @ExceptionHandler({RecursoYaExisteException.class, ConflictoException.class})
    public ResponseEntity<ErrorRespuestaDTO> conflicto(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorRespuestaDTO.de("Conflicto", e.getMessage()));
    }

    // ------------------------------------------------------ errores del broker

    /**
     * Un error de CANAL de AMQP trae un código de respuesta que ya dice qué
     * pasó: 404 NOT_FOUND (no existe el exchange/cola), 403 ACCESS_REFUSED
     * (amq.*, permisos), 406 PRECONDITION_FAILED (redeclarar con otros
     * argumentos, if-empty con mensajes), 405 RESOURCE_LOCKED (cola exclusiva).
     * Se traduce a HTTP en vez de devolver un 500 genérico. Sin código de canal
     * (conexión rechazada, credenciales, timeout) el broker no está disponible: 503.
     */
    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<ErrorRespuestaDTO> errorAmqp(AmqpException e) {
        ShutdownSignalException cierre = buscarCierre(e);
        if (cierre != null && !cierre.isHardError() && cierre.getReason() instanceof AMQP.Channel.Close canal) {
            HttpStatus estado = switch (canal.getReplyCode()) {
                case 404 -> HttpStatus.NOT_FOUND;
                case 403 -> HttpStatus.FORBIDDEN;
                case 405, 406 -> HttpStatus.CONFLICT;
                default -> HttpStatus.BAD_GATEWAY;
            };
            log.warn("RabbitMQ rechazó la operación ({}): {}", canal.getReplyCode(), canal.getReplyText());
            return ResponseEntity.status(estado)
                    .body(ErrorRespuestaDTO.de("RabbitMQ rechazó la operación", canal.getReplyText()));
        }
        if (cierre != null || e instanceof AmqpConnectException || e instanceof AmqpAuthenticationException
                || e instanceof AmqpTimeoutException) {
            log.error("RabbitMQ no disponible: {}", mensajeRaiz(e));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorRespuestaDTO.de("RabbitMQ no disponible",
                            "No se pudo operar contra ningún nodo del clúster: " + mensajeRaiz(e)));
        }
        log.error("Error AMQP no clasificado", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorRespuestaDTO.de("Error de RabbitMQ", mensajeRaiz(e)));
    }

    @ExceptionHandler(ManagementNoDisponibleException.class)
    public ResponseEntity<ErrorRespuestaDTO> managementCaida(ManagementNoDisponibleException e) {
        log.error(e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorRespuestaDTO.de("API de management no disponible", e.getMessage()));
    }

    /** La API de management respondió, pero con error (401 credenciales, 403 sin tag de monitoreo). */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ErrorRespuestaDTO> managementRechazo(RestClientResponseException e) {
        log.error("La API de management respondió {}: {}", e.getStatusCode(), e.getStatusText());
        String detalle = e.getStatusCode().value() == 401
                ? "Credenciales rechazadas (revisar RABBITMQ_USER / RABBITMQ_PASS)"
                : "Respondió " + e.getStatusCode().value() + " " + e.getStatusText();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorRespuestaDTO.de("La API de management rechazó la consulta", detalle));
    }

    // -------------------------------------------------------------- genéricos

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorRespuestaDTO> rutaInexistente(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorRespuestaDTO.de("No encontrado", e.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorRespuestaDTO> metodoNoPermitido(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorRespuestaDTO.de("Método no permitido", e.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorRespuestaDTO> tipoNoSoportado(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorRespuestaDTO.de("Tipo de contenido no soportado", "Usar Content-Type: application/json"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuestaDTO> inesperado(Exception e) {
        log.error("Error no controlado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorRespuestaDTO.de("Error interno", e.getClass().getSimpleName()));
    }

    private static ShutdownSignalException buscarCierre(Throwable t) {
        for (Throwable actual = t; actual != null; actual = actual.getCause()) {
            if (actual instanceof ShutdownSignalException sse) {
                return sse;
            }
            if (actual.getCause() == actual) {
                break;
            }
        }
        return null;
    }

    private static String mensajeRaiz(Throwable t) {
        Throwable raiz = t;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        return raiz.getMessage() != null ? raiz.getMessage() : raiz.getClass().getSimpleName();
    }
}
