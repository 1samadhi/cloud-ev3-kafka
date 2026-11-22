package com.pedidos360.carrito.Controller;

import com.pedidos360.carrito.Component.UsuarioActual;
import com.pedidos360.carrito.DTO.PedidoDTO;
import com.pedidos360.carrito.Service.ServicioPedidos;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Pedidos del usuario. POST reserva stock en ms-productos (409 con faltantes se propaga) y deja el
 * pedido en PENDIENTE_PAGO; luego la tienda llama a ms-pago con transactionId = id del pedido.
 */
@Validated
@RestController
@RequestMapping("/api/pedidos")
@RequiredArgsConstructor
public class PedidosController {

    private final ServicioPedidos servicioPedidos;
    private final UsuarioActual usuarioActual;

    @PostMapping
    public ResponseEntity<PedidoDTO> crear() {
        PedidoDTO pedido = servicioPedidos.crearPedido(usuarioActual.obtener());
        return ResponseEntity.created(URI.create("/api/pedidos/" + pedido.id())).body(pedido);
    }

    @GetMapping
    public List<PedidoDTO> listar() {
        return servicioPedidos.listarDelUsuario(usuarioActual.obtener());
    }

    @GetMapping("/{id}")
    public PedidoDTO obtener(@PathVariable @Size(max = 20, message = "id admite hasta 20 caracteres") String id) {
        return servicioPedidos.obtenerDelUsuario(usuarioActual.obtener(), id);
    }
}
