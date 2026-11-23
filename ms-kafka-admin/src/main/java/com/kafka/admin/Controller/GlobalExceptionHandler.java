package com.kafka.admin.Controller;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.kafka.admin.Exception.ConflictoException;
import com.kafka.admin.Exception.KafkaNoDisponibleException;
import com.kafka.admin.Exception.RecursoNoEncontradoException;
import com.kafka.admin.Exception.SolicitudInvalidaException;
import com.kafka.admin.Model.ErrorResponseDTO;
import com.kafka.admin.Model.KafkaAdminException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Manejador global de excepciones: traduce los errores del AdminClient y de la
 * validación a respuestas HTTP legibles, sin stacktraces.
 *
 * Forma de toda respuesta: { error, detalle, mensaje, codigo, timestamp }.
 * Los 4xx se loguean en WARN (es el cliente el que se equivocó) y los 5xx en
 * ERROR con la traza (es nuestro problema o del clúster).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SolicitudInvalidaException.class)
    public ResponseEntity<ErrorResponseDTO> solicitudInvalida(SolicitudInvalidaException e) {
        return responder(HttpStatus.BAD_REQUEST, "Solicitud inválida", e.getMessage(), e);
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorResponseDTO> noEncontrado(RecursoNoEncontradoException e) {
        return responder(HttpStatus.NOT_FOUND, "No encontrado", e.getMessage(), e);
    }

    @ExceptionHandler(ConflictoException.class)
    public ResponseEntity<ErrorResponseDTO> conflicto(ConflictoException e) {
        return responder(HttpStatus.CONFLICT, "Conflicto", e.getMessage(), e);
    }

    @ExceptionHandler(KafkaNoDisponibleException.class)
    public ResponseEntity<ErrorResponseDTO> kafkaNoDisponible(KafkaNoDisponibleException e) {
        return responder(HttpStatus.SERVICE_UNAVAILABLE, "Kafka no disponible", e.getMessage(), e);
    }

    /**
     * Excepción genérica del servicio. El servicio ya lanza las subclases
     * tipadas; esto queda como red para cualquier KafkaAdminException sin tipo
     * (se conserva el criterio anterior por texto para no cambiar respuestas).
     */
    @ExceptionHandler(KafkaAdminException.class)
    public ResponseEntity<ErrorResponseDTO> handleKafkaAdminException(KafkaAdminException e) {
        String mensaje = e.getMessage() == null ? "" : e.getMessage();
        if (mensaje.contains("no existe")) {
            return responder(HttpStatus.NOT_FOUND, "No encontrado", mensaje, e);
        }
        if (mensaje.contains("ya existe")) {
            return responder(HttpStatus.CONFLICT, "Conflicto", mensaje, e);
        }
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error de Kafka", mensaje, e);
    }

    /** @Valid sobre el cuerpo: se listan todos los campos inválidos de una vez. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> validacion(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getAllErrors().stream()
                .map(err -> err.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return responder(HttpStatus.BAD_REQUEST, "Solicitud inválida", detalle, e);
    }

    /** JSON mal formado o valor de enum desconocido (p. ej. estrategia "PRIMERO"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDTO> cuerpoIlegible(HttpMessageNotReadableException e) {
        String detalle = "El cuerpo de la petición no es un JSON válido o falta";
        if (e.getCause() instanceof InvalidFormatException ife && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            String campo = ife.getPath().isEmpty() ? "?" : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            detalle = "Valor '" + ife.getValue() + "' inválido para '" + campo + "'; valores aceptados: "
                    + Arrays.toString(ife.getTargetType().getEnumConstants());
        } else if (e.getCause() instanceof InvalidFormatException ife) {
            String campo = ife.getPath().isEmpty() ? "?" : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            detalle = "Valor '" + ife.getValue() + "' inválido para '" + campo + "'";
        }
        return responder(HttpStatus.BAD_REQUEST, "Solicitud inválida", detalle, e);
    }

    /** Variable de ruta con tipo incorrecto (p. ej. /api/brokers/uno/configuracion). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDTO> tipoIncorrecto(MethodArgumentTypeMismatchException e) {
        return responder(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El parámetro '" + e.getName() + "' no admite el valor '" + e.getValue() + "'", e);
    }

    /*
     * Sin estos tres, el handler genérico de Exception convertía una ruta
     * inexistente o un verbo equivocado en un 500, que hace creer que el
     * servicio falló.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponseDTO> rutaInexistente(NoResourceFoundException e) {
        return responder(HttpStatus.NOT_FOUND, "No encontrado", "La ruta '/" + e.getResourcePath() + "' no existe", e);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponseDTO> metodoNoSoportado(HttpRequestMethodNotSupportedException e) {
        return responder(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido",
                "El método " + e.getMethod() + " no está permitido en esta ruta", e);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponseDTO> tipoContenido(HttpMediaTypeNotSupportedException e) {
        return responder(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado",
                "Envíe el cuerpo como application/json", e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGenericException(Exception e) {
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Error interno del servidor: " + e.getMessage(), e);
    }

    private static ResponseEntity<ErrorResponseDTO> responder(HttpStatus estado, String error, String detalle,
                                                              Exception e) {
        if (estado.is5xxServerError()) {
            log.error("{} {}: {}", estado.value(), error, detalle, e);
        } else {
            log.warn("{} {}: {}", estado.value(), error, detalle);
        }
        return ResponseEntity.status(estado).body(new ErrorResponseDTO(error, detalle, estado.value()));
    }
}
