package com.pedidos360.rabbitadmin.Controller;

import com.pedidos360.rabbitadmin.DTO.BindingDTO;
import com.pedidos360.rabbitadmin.DTO.BindingRequestDTO;
import com.pedidos360.rabbitadmin.DTO.ColaDTO;
import com.pedidos360.rabbitadmin.DTO.CrearColaRequestDTO;
import com.pedidos360.rabbitadmin.DTO.CrearExchangeRequestDTO;
import com.pedidos360.rabbitadmin.DTO.EstadoClusterDTO;
import com.pedidos360.rabbitadmin.DTO.ExchangeDTO;
import com.pedidos360.rabbitadmin.DTO.RespuestaOperacionDTO;
import com.pedidos360.rabbitadmin.Service.ServicioRabbitAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API de administración de RabbitMQ (PLAN-INTEGRACION-EV3 §11). Sólo HTTP:
 * validación de entrada y códigos; las reglas viven en ServicioRabbitAdmin.
 */
@RestController
@RequestMapping("/api/rabbit")
@RequiredArgsConstructor
public class RabbitAdminController {

    private final ServicioRabbitAdmin servicio;

    // ------------------------------------------------------------------ colas

    @GetMapping("/colas")
    public List<ColaDTO> listarColas(@RequestParam(defaultValue = "false") boolean soloConMensajes) {
        return servicio.listarColas(soloConMensajes);
    }

    @PostMapping("/colas")
    public ResponseEntity<RespuestaOperacionDTO> crearCola(@Valid @RequestBody CrearColaRequestDTO req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crearCola(req));
    }

    @DeleteMapping("/colas/{nombre}")
    public RespuestaOperacionDTO borrarCola(@PathVariable @NotBlank String nombre,
                                            @RequestParam(defaultValue = "false") boolean siVacia,
                                            @RequestParam(defaultValue = "false") boolean forzar) {
        return servicio.borrarCola(nombre, siVacia, forzar);
    }

    @PostMapping("/colas/{nombre}/purgar")
    public RespuestaOperacionDTO purgarCola(@PathVariable @NotBlank String nombre) {
        return servicio.purgarCola(nombre);
    }

    // -------------------------------------------------------------- exchanges

    @GetMapping("/exchanges")
    public List<ExchangeDTO> listarExchanges() {
        return servicio.listarExchanges();
    }

    @PostMapping("/exchanges")
    public ResponseEntity<RespuestaOperacionDTO> crearExchange(@Valid @RequestBody CrearExchangeRequestDTO req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crearExchange(req));
    }

    @DeleteMapping("/exchanges/{nombre}")
    public RespuestaOperacionDTO borrarExchange(@PathVariable @NotBlank String nombre,
                                                @RequestParam(defaultValue = "false") boolean forzar) {
        return servicio.borrarExchange(nombre, forzar);
    }

    // --------------------------------------------------------------- bindings

    @GetMapping("/bindings")
    public List<BindingDTO> listarBindings(@RequestParam(defaultValue = "false") boolean incluirPorDefecto) {
        return servicio.listarBindings(incluirPorDefecto);
    }

    @PostMapping("/bindings")
    public ResponseEntity<RespuestaOperacionDTO> crearBinding(@Valid @RequestBody BindingRequestDTO req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crearBinding(req));
    }

    /** Con cuerpo: un binding se identifica por origen + destino + routing key (+ argumentos). */
    @DeleteMapping("/bindings")
    public RespuestaOperacionDTO borrarBinding(@Valid @RequestBody BindingRequestDTO req,
                                               @RequestParam(defaultValue = "false") boolean forzar) {
        return servicio.borrarBinding(req, forzar);
    }

    // ---------------------------------------------------------------- cluster

    @GetMapping("/cluster")
    public EstadoClusterDTO cluster() {
        return servicio.estadoCluster();
    }
}
