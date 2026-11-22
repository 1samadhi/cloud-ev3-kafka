package com.pedidos360.carrito.Controller;

import com.pedidos360.carrito.Component.UsuarioActual;
import com.pedidos360.carrito.DTO.ActualizarCantidadDTO;
import com.pedidos360.carrito.DTO.AgregarItemDTO;
import com.pedidos360.carrito.DTO.AplicarCuponDTO;
import com.pedidos360.carrito.DTO.CarritoDTO;
import com.pedidos360.carrito.DTO.EnvioDTO;
import com.pedidos360.carrito.DTO.SucursalDTO;
import com.pedidos360.carrito.Service.ServicioCarrito;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Carrito del usuario (X-Usuario-Id). Sólo valida y delega: las reglas están en ServicioCarrito. */
@Validated
@RestController
@RequestMapping("/api/carrito")
@RequiredArgsConstructor
public class CarritoController {

    private final ServicioCarrito servicioCarrito;
    private final UsuarioActual usuarioActual;

    @GetMapping
    public CarritoDTO obtener() {
        return servicioCarrito.obtener(usuarioActual.obtener());
    }

    /** Publica AGREGAR_CARRITO (Kafka) y carrito.producto-agregado (RabbitMQ). */
    @PostMapping("/items")
    public CarritoDTO agregar(@Valid @RequestBody AgregarItemDTO peticion) {
        return servicioCarrito.agregarItem(usuarioActual.obtener(), peticion);
    }

    /** cantidad = 0 quita la línea. */
    @PatchMapping("/items/{productoId}")
    public CarritoDTO actualizar(@PathVariable @Size(max = 40, message = "productoId admite hasta 40 caracteres") String productoId,
                                 @Valid @RequestBody ActualizarCantidadDTO peticion) {
        return servicioCarrito.actualizarCantidad(usuarioActual.obtener(), productoId, peticion.cantidad());
    }

    @DeleteMapping
    public CarritoDTO vaciar() {
        return servicioCarrito.vaciar(usuarioActual.obtener());
    }

    /** 404 si el cupón no existe; 422 si no alcanza el mínimo. Publica APLICAR_CUPON. */
    @PostMapping("/cupon")
    public CarritoDTO aplicarCupon(@Valid @RequestBody AplicarCuponDTO peticion) {
        return servicioCarrito.aplicarCupon(usuarioActual.obtener(), peticion.codigo());
    }

    @DeleteMapping("/cupon")
    public CarritoDTO quitarCupon() {
        return servicioCarrito.quitarCupon(usuarioActual.obtener());
    }

    /** Publica GESTIONAR_ENVIO. */
    @PutMapping("/envio")
    public CarritoDTO configurarEnvio(@Valid @RequestBody EnvioDTO peticion) {
        return servicioCarrito.configurarEnvio(usuarioActual.obtener(), peticion);
    }

    /** Público (sin usuario): la tienda arma el selector de retiro con esta lista cerrada. */
    @GetMapping("/sucursales")
    public List<SucursalDTO> sucursales() {
        return servicioCarrito.sucursales();
    }
}
