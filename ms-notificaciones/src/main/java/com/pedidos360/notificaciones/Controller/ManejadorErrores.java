package com.pedidos360.notificaciones.Controller;

import com.pedidos360.notificaciones.DTO.ErrorRespuestaDTO;
import com.pedidos360.notificaciones.Exception.NotificacionNoEncontradaException;
import com.pedidos360.notificaciones.Exception.UsuarioNoIdentificadoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/** Errores HTTP con la forma { error, detalle, timestamp } (§3.5). */
@Slf4j
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler({UsuarioNoIdentificadoException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorRespuestaDTO> peticionInvalida(Exception e) {
        return ResponseEntity.badRequest().body(ErrorRespuestaDTO.de("Petición inválida", e.getMessage()));
    }

    @ExceptionHandler(NotificacionNoEncontradaException.class)
    public ResponseEntity<ErrorRespuestaDTO> noEncontrada(NotificacionNoEncontradaException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorRespuestaDTO.de("No encontrada", e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorRespuestaDTO> baseNoDisponible(DataAccessException e) {
        log.error("Error de base de datos: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorRespuestaDTO.de("Base de datos no disponible", "Intente nuevamente en unos segundos"));
    }

    /** Ruta inexistente: 404 y no un 500 del manejador genérico. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorRespuestaDTO> rutaInexistente(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorRespuestaDTO.de("No encontrada", e.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorRespuestaDTO> metodoNoPermitido(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorRespuestaDTO.de("Método no permitido", e.getMessage()));
    }

    /** El navegador cerró la pestaña con el SSE abierto: no hay a quién responder. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clienteDesconectado() {
        // Nada que hacer: la conexión ya no existe.
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuestaDTO> inesperado(Exception e) {
        log.error("Error no controlado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorRespuestaDTO.de("Error interno", e.getClass().getSimpleName()));
    }
}
