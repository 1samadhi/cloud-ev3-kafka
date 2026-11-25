package com.pedidos360.productos.Controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pedidos360.productos.Component.UsuarioActual;
import com.pedidos360.productos.DTO.CategoriaDTO;
import com.pedidos360.productos.DTO.ProductoDTO;
import com.pedidos360.productos.Service.OrdenCatalogo;
import com.pedidos360.productos.Service.ServicioCatalogo;

import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Catálogo público que consume la tienda. Sin reglas de negocio: valida y delega. */
@Validated
@RestController
@RequestMapping("/api/productos")
@RequiredArgsConstructor
public class ProductoController {

    private final ServicioCatalogo servicioCatalogo;
    private final UsuarioActual usuarioActual;

    @GetMapping
    public List<ProductoDTO> listar(
            @RequestParam(required = false) @Size(max = 40, message = "categoria admite como máximo 40 caracteres") String categoria,
            @RequestParam(required = false) @Size(max = 100, message = "q admite como máximo 100 caracteres") String q,
            @RequestParam(required = false) String orden) {
        return servicioCatalogo.listar(categoria, q, OrdenCatalogo.desde(orden));
    }

    @GetMapping("/categorias")
    public List<CategoriaDTO> categorias() {
        return servicioCatalogo.categorias();
    }

    /** {@code X-Usuario-Id} es opcional: con usuario se publica VER_PRODUCTO; anónimo sólo lee. */
    @GetMapping("/{id}")
    public ProductoDTO detalle(@PathVariable @Size(max = 64, message = "id admite como máximo 64 caracteres") String id) {
        return servicioCatalogo.obtenerDetalle(id, usuarioActual.idUsuario());
    }
}
