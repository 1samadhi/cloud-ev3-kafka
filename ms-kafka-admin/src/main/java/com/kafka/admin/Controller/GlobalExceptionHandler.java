package com.kafka.admin.Controller;

import com.kafka.admin.Model.ErrorResponseDTO;
import com.kafka.admin.Model.KafkaAdminException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Manejador global de excepciones para traducir errores del AdminClient
 * a respuestas HTTP legibles sin stacktraces.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(KafkaAdminException.class)
    public ResponseEntity<ErrorResponseDTO> handleKafkaAdminException(KafkaAdminException e) {
        String mensaje = e.getMessage();

        // Determinar el código HTTP basado en el mensaje
        int statusCode;
        if (mensaje.contains("no existe")) {
            statusCode = 404;
        } else if (mensaje.contains("Replication factor") || 
                   mensaje.contains("no puede estar vacío") ||
                   mensaje.contains("no puede contener espacios") ||
                   mensaje.contains("deben ser >= 1") ||
                   mensaje.contains("no se pueden eliminar") ||
                   mensaje.contains("en uso del proyecto")) {
            statusCode = 400;
        } else if (mensaje.contains("ya existe")) {
            statusCode = 409;
        } else {
            statusCode = 500;
        }

        ErrorResponseDTO error = new ErrorResponseDTO(mensaje, statusCode);
        return ResponseEntity.status(statusCode).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGenericException(Exception e) {
        ErrorResponseDTO error = new ErrorResponseDTO(
                "Error interno del servidor: " + e.getMessage(),
                500
        );
        return ResponseEntity.status(500).body(error);
    }
}
