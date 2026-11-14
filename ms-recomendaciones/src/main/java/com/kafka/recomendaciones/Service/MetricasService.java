package com.kafka.recomendaciones.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kafka.recomendaciones.Config.ProductoConfig;
import com.kafka.recomendaciones.DTO.MetricasDTO;
import com.kafka.recomendaciones.DTO.ProductoVistoDTO;
import com.kafka.recomendaciones.Model.Producto;
import com.kafka.recomendaciones.Repository.InteraccionesRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio dedicado a la lectura y cálculo de métricas de operación.
 * Se separó de RecomendacionesService para mantener la lógica de métricas
 * desacoplada del cálculo de recomendaciones, siguiendo el principio de responsabilidad única.
 */
@Service
public class MetricasService {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricasService.class);

    @Autowired
    private InteraccionesRepository interaccionesRepository;

    @Autowired
    private ProductoConfig productCatalog;

    /**
     * Obtiene las métricas en tiempo real para el dashboard de operaciones.
     *
     * - productosMasVistos: Top 5 de productos por cantidad total de interacciones,
     *   enriquecidos con nombre y categoría del catálogo.
     * - interaccionesPorMinuto: cantidad de interacciones en los últimos 60 segundos.
     * - usuariosActivos: cantidad de usuarios únicos con interacciones en los últimos 5 minutos.
     * - totalInteracciones: total de filas en la tabla de interacciones.
     *
     * Si la tabla está vacía, devuelve estructura con valores por defecto (listas vacías, ceros).
     */
    public MetricasDTO obtenerMetricas() {
        logger.info("Iniciando lectura de métricas");
        long ahora = System.currentTimeMillis();
        long hace60Segundos = ahora - 60_000L;      // 60000 ms
        long hace5Minutos = ahora - 300_000L;       // 300000 ms

        // Total de interacciones
        long totalInteracciones = interaccionesRepository.count();
        logger.info("Total interacciones: {}", totalInteracciones);

        // Interacciones en el último minuto
        long interaccionesPorMinuto = interaccionesRepository.countInteraccionesPorVentana(hace60Segundos);
        logger.info("Interacciones por minuto: {}", interaccionesPorMinuto);

        // Usuarios activos en los últimos 5 minutos
        long usuariosActivos = interaccionesRepository.countUsuariosActivosPorVentana(hace5Minutos);
        logger.info("Usuarios activos: {}", usuariosActivos);

        // Productos más vistos: Top 5
        List<ProductoVistoDTO> productosMasVistos = obtenerProductosMasVistos();
        logger.info("Productos más vistos obtenidos: {} items", productosMasVistos.size());

        return new MetricasDTO(
            productosMasVistos,
            interaccionesPorMinuto,
            usuariosActivos,
            totalInteracciones
        );
    }

    /**
     * Obtiene el Top 5 de productos ordenados por cantidad de interacciones.
     * Enriquece cada entrada con el nombre y categoría del catálogo.
     * Si no encuentra el producto en el catálogo, deja el nombre en null
     * y conserva la categoría guardada en la tabla.
     */
    private List<ProductoVistoDTO> obtenerProductosMasVistos() {
        logger.info("Consultando productos más vistos");
        // Obtiene los productos con más interacciones (sin límite, se recorta luego)
        List<Object> resultados = interaccionesRepository.findTop5ProductsByInteractionCount();
        logger.info("Resultados de BD: {} registros", resultados.size());

        // Procesa y enriquece los resultados
        List<ProductoVistoDTO> productos = resultados.stream()
            .limit(5)
            .map(obj -> {
                try {
                    logger.debug("Procesando objeto de tipo: {}", obj.getClass().getName());
                    // La consulta nativa devuelve Object[] con [itemId, count]
                    Object[] row = (Object[]) obj;
                    String itemId = (String) row[0];
                    long count = ((Number) row[1]).longValue();
                    logger.debug("Item: {}, Count: {}", itemId, count);

                    ProductoVistoDTO dto = new ProductoVistoDTO();
                    dto.setItemId(itemId);
                    dto.setVistas(count);

                    // Intenta enriquecer con el catálogo
                    Optional<Producto> producto = productCatalog.buscarPorId(itemId);
                    if (producto.isPresent()) {
                        dto.setNombre(producto.get().getNombre());
                        dto.setCategoria(producto.get().getCategoria());
                        logger.debug("Producto enriquecido: {} ({}) en {}", itemId, dto.getNombre(), dto.getCategoria());
                    } else {
                        // Si no está en catálogo, deja nombre en null
                        // La categoría será null también a menos que la recuperemos de BD
                        dto.setNombre(null);
                        dto.setCategoria(null);
                        logger.debug("Producto no encontrado en catálogo: {}", itemId);
                    }

                    return dto;
                } catch (Exception e) {
                    logger.error("Error procesando resultado: {}", obj, e);
                    throw new RuntimeException("Error en procesamiento de métricas", e);
                }
            })
            .collect(Collectors.toList());
        
        logger.info("Productos procesados: {}", productos.size());
        return productos;
    }
}
