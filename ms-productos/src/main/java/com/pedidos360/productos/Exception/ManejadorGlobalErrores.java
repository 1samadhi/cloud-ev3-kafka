package com.pedidos360.productos.Exception;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pedidos360.productos.DTO.ErrorDTO;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Traduce excepciones a la forma {@code { error, detalle, timestamp }} con el código HTTP
 * correcto (§3.5). Los 4xx se loguean en WARN (es el cliente el que se equivocó); sólo lo
 * inesperado llega a ERROR con stacktrace.
 */
@Slf4j
@RestControllerAdvice
public class ManejadorGlobalErrores {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDTO> validacion(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        log.warn("Petición rechazada por validación: {}", detalle);
        return ResponseEntity.badRequest().body(ErrorDTO.de("Petición inválida", detalle));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorDTO> validacionParametros(HandlerMethodValidationException ex) {
        String detalle = ex.getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Parámetros inválidos: {}", detalle);
        return ResponseEntity.badRequest().body(ErrorDTO.de("Petición inválida", detalle));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorDTO> restriccion(ConstraintViolationException ex) {
        log.warn("Restricción violada: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorDTO.de("Petición inválida", ex.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorDTO> malFormada(Exception ex) {
        log.warn("Petición mal formada: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorDTO.de("Petición mal formada",
                "El cuerpo o los parámetros no tienen el formato esperado"));
    }

    @ExceptionHandler(PeticionInvalidaException.class)
    public ResponseEntity<ErrorDTO> peticionInvalida(PeticionInvalidaException ex) {
        log.warn("Petición inválida: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorDTO.de("Petición inválida", ex.getMessage()));
    }

    @ExceptionHandler(ProductoNoEncontradoException.class)
    public ResponseEntity<ErrorDTO> noEncontrado(ProductoNoEncontradoException ex) {
        log.warn("Producto no encontrado: {}", ex.getProductoId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorDTO.de("Producto no encontrado", ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorDTO> rutaInexistente(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorDTO.de("Recurso no encontrado", ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorDTO> metodoNoSoportado(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorDTO.de("Método no permitido", ex.getMessage()));
    }

    @ExceptionHandler(StockInsuficienteException.class)
    public ResponseEntity<ErrorDTO> stockInsuficiente(StockInsuficienteException ex) {
        log.warn("Reserva rechazada (todo o nada): faltantes={}", ex.getFaltantes());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDTO("Stock insuficiente",
                "La reserva se rechazó completa: ningún producto fue descontado", ex.getFaltantes(),
                System.currentTimeMillis()));
    }

    @ExceptionHandler(ConflictoReservaException.class)
    public ResponseEntity<ErrorDTO> conflictoReserva(ConflictoReservaException ex) {
        log.warn("Conflicto de reserva: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorDTO.de("Conflicto de reserva", ex.getMessage()));
    }

    /** Dos reservas simultáneas del MISMO idPedido: la PK de reserva_stock rechaza la segunda. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorDTO> integridad(DataIntegrityViolationException ex) {
        log.warn("Violación de integridad (probable reserva duplicada concurrente): {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorDTO.de("Conflicto",
                "La operación chocó con otra simultánea sobre el mismo pedido; reintente"));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorDTO> concurrenciaOptimista(ObjectOptimisticLockingFailureException ex) {
        log.warn("Conflicto de versión (@Version) sobre {}: {}", ex.getPersistentClassName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorDTO.de("Conflicto de concurrencia",
                "Otro proceso modificó el mismo producto; reintente"));
    }

    /** No se obtuvo el bloqueo de fila a tiempo: es transitorio, por eso 503 y no 409. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorDTO> bloqueoNoObtenido(PessimisticLockingFailureException ex) {
        log.warn("No se obtuvo el bloqueo de fila a tiempo: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorDTO.de("Servicio ocupado",
                "El stock está siendo modificado por otra operación; reintente en unos segundos"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDTO> inesperado(Exception ex) {
        log.error("Error inesperado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorDTO.de("Error interno",
                "Ocurrió un error inesperado"));
    }
}
