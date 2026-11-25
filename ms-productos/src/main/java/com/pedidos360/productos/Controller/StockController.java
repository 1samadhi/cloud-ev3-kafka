package com.pedidos360.productos.Controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.productos.DTO.PeticionReposicionDTO;
import com.pedidos360.productos.DTO.PeticionReservaDTO;
import com.pedidos360.productos.DTO.ProductoDTO;
import com.pedidos360.productos.DTO.RespuestaReservaDTO;
import com.pedidos360.productos.DTO.StockAdminDTO;
import com.pedidos360.productos.Service.ServicioCatalogo;
import com.pedidos360.productos.Service.ServicioStock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Comandos de stock. Reservas y liberaciones las llama ms-carrito (REST, no Kafka: el
 * pedido necesita saber en el acto si hubo stock). Reposición y vista de stock son del
 * panel admin; hasta la fase 4 (Cognito) no tienen control de rol.
 */
@Validated
@RestController
@RequestMapping("/api/productos")
@RequiredArgsConstructor
public class StockController {

    private final ServicioStock servicioStock;
    private final ServicioCatalogo servicioCatalogo;

    @PostMapping("/stock/reservas")
    public RespuestaReservaDTO reservar(@Valid @RequestBody PeticionReservaDTO peticion) {
        return servicioStock.reservar(peticion);
    }

    @PostMapping("/stock/liberaciones")
    public RespuestaReservaDTO liberar(@Valid @RequestBody PeticionReservaDTO peticion) {
        return servicioStock.liberar(peticion);
    }

    @PutMapping("/{id}/stock")
    public ProductoDTO reponer(@PathVariable @Size(max = 64, message = "id admite como máximo 64 caracteres") String id,
                               @Valid @RequestBody PeticionReposicionDTO peticion) {
        return servicioStock.reponer(id, peticion.getCantidad());
    }

    @GetMapping("/admin/stock")
    public List<StockAdminDTO> stockAdmin() {
        return servicioCatalogo.stockAdmin();
    }
}
