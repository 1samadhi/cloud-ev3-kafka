package com.pedidos360.carrito.Exception;

import com.pedidos360.carrito.DTO.ErrorRespuestaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/** Traduce excepciones a { error, detalle, timestamp } con el código HTTP correcto (§3.5). */
@Slf4j
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuestaDTO> validacion(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return responder(HttpStatus.BAD_REQUEST, "Datos inválidos", detalle);
    }

    /** @Size/@Pattern sobre @PathVariable o @RequestParam (controllers con @Validated). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorRespuestaDTO> validacionParametros(HandlerMethodValidationException e) {
        String detalle = e.getAllErrors().stream()
                .map(err -> err.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return responder(HttpStatus.BAD_REQUEST, "Datos inválidos", detalle);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorRespuestaDTO> cuerpoIlegible(HttpMessageNotReadableException e) {
        return responder(HttpStatus.BAD_REQUEST, "Cuerpo de la petición ilegible",
                "JSON mal formado o valor fuera de los permitidos (p. ej. metodo debe ser ENVIO o RETIRO)");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorRespuestaDTO> tipoInvalido(MethodArgumentTypeMismatchException e) {
        return responder(HttpStatus.BAD_REQUEST, "Parámetro inválido", e.getName() + ": valor no permitido");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorRespuestaDTO> faltaHeader(MissingRequestHeaderException e) {
        return responder(HttpStatus.BAD_REQUEST, "Falta un header obligatorio", e.getHeaderName());
    }

    @ExceptionHandler({UsuarioNoIdentificadoException.class, DatosInvalidosException.class})
    public ResponseEntity<ErrorRespuestaDTO> datosInvalidos(RuntimeException e) {
        return responder(HttpStatus.BAD_REQUEST, "Datos inválidos", e.getMessage());
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorRespuestaDTO> noEncontrado(RecursoNoEncontradoException e) {
        return responder(HttpStatus.NOT_FOUND, "No encontrado", e.getMessage());
    }

    @ExceptionHandler(StockInsuficienteException.class)
    public ResponseEntity<ErrorRespuestaDTO> sinStock(StockInsuficienteException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorRespuestaDTO(
                "Stock insuficiente", e.getMessage(), System.currentTimeMillis(), e.getFaltantes()));
    }

    @ExceptionHandler(TransicionInvalidaException.class)
    public ResponseEntity<ErrorRespuestaDTO> transicion(TransicionInvalidaException e) {
        return responder(HttpStatus.CONFLICT, "Transición de estado inválida", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorRespuestaDTO> concurrencia(ObjectOptimisticLockingFailureException e) {
        return responder(HttpStatus.CONFLICT, "Conflicto de concurrencia",
                "El recurso cambió mientras se procesaba la petición; vuelve a intentarlo");
    }

    @ExceptionHandler(ReglaNegocioException.class)
    public ResponseEntity<ErrorRespuestaDTO> reglaNegocio(ReglaNegocioException e) {
        return responder(HttpStatus.UNPROCESSABLE_ENTITY, "Operación no permitida", e.getMessage());
    }

    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<ErrorRespuestaDTO> noDisponible(ServicioNoDisponibleException e) {
        log.error("Dependencia no disponible: {}", e.getMessage());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuestaDTO> inesperado(Exception e) {
        // Las excepciones propias de Spring MVC (ruta inexistente, método no soportado...) ya
        // traen su código HTTP: se respetan en vez de convertirlas en un 500 engañoso.
        if (e instanceof ErrorResponse respuesta) {
            HttpStatus status = HttpStatus.resolve(respuesta.getStatusCode().value());
            return responder(status == null ? HttpStatus.BAD_REQUEST : status,
                    "Petición no atendida", e.getMessage());
        }
        // El detalle real va al log; al cliente no se le filtran trazas internas.
        log.error("Error inesperado atendiendo la petición", e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "Error inesperado; revisar logs de ms-carrito");
    }

    private ResponseEntity<ErrorRespuestaDTO> responder(HttpStatus status, String error, String detalle) {
        return ResponseEntity.status(status).body(ErrorRespuestaDTO.de(error, detalle));
    }
}
