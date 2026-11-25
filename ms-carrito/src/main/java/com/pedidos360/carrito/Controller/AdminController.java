package com.pedidos360.carrito.Controller;

import com.pedidos360.carrito.DTO.PedidoDTO;
import com.pedidos360.carrito.DTO.ResumenAdminDTO;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Service.ServicioPedidos;
import com.pedidos360.carrito.Service.ServicioResumenAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Panel admin de la tienda. Sin control de rol hasta la fase 4 (Cognito, §12). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ServicioResumenAdmin servicioResumenAdmin;
    private final ServicioPedidos servicioPedidos;

    @GetMapping("/resumen")
    public ResumenAdminDTO resumen() {
        return servicioResumenAdmin.resumen();
    }

    /** estado opcional; un valor fuera del enum responde 400 (MethodArgumentTypeMismatchException). */
    @GetMapping("/pedidos")
    public List<PedidoDTO> pedidos(@RequestParam(required = false) EstadoPedido estado) {
        return servicioPedidos.listarTodos(estado);
    }
}
