package com.kafka.recomendaciones.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kafka.recomendaciones.Config.ProductoConfig;
import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.Model.Interacciones;
import com.kafka.recomendaciones.Model.Producto;
import com.kafka.recomendaciones.Model.Recomendaciones;
import com.kafka.recomendaciones.Repository.InteraccionesRepository;
import com.kafka.recomendaciones.Repository.RecomendacionesRepository;

import java.util.*;
import java.util.stream.Collectors;


@Service
public class RecomendacionesService {

    @Autowired 
    private  InteraccionesRepository interaccionesRepository;
    @Autowired 
    private  RecomendacionesRepository recomendacionesRepository;
    @Autowired 
    private  ProductoConfig productCatalog;

    @Transactional
    public void calcularYGuardarTop5(String userId) {
        List<Interacciones> interacciones = interaccionesRepository.findByUserId(userId);
        if (interacciones.isEmpty()) return;

        Map<String, Double> itemScores = new HashMap<>();
        for (Interacciones inter : interacciones) {
            itemScores.put(inter.getItemId(), itemScores.getOrDefault(inter.getItemId(), 0.0) + inter.getWeight());
        }

        // Ordenar y recortar al Top 5
        var top5Entries = itemScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(5)
            .collect(Collectors.toList());

        recomendacionesRepository.deleteByUserId(userId);

        List<Recomendaciones> nuevasRecomendaciones = new ArrayList<>();
        int rank = 1;

        for (Map.Entry<String, Double> entry : top5Entries) {
            String itemId = entry.getKey();
            double score = entry.getValue();

            // Buscar la categoría y tipo desde el catálogo en memoria (o valores por defecto)
            Producto producto = productCatalog.buscarPorId(itemId).orElse(null);
            String categoria = producto != null ? producto.getCategoria() : "general";
            String productType = producto != null ? producto.getProductType() : "standard";

            Recomendaciones rec = new Recomendaciones(
                userId, itemId, score, rank++, categoria, productType
            );
            nuevasRecomendaciones.add(rec);
        }

        recomendacionesRepository.saveAll(nuevasRecomendaciones);
    }

    /**
     * Top 5 crudo, tal como esta persistido. Se conserva para uso interno.
     */
    public List<Recomendaciones> obtenerTop5(String userId) {
        return recomendacionesRepository.findByUserIdOrderByRankPositionAsc(userId);
    }

    /**
     * Top 5 enriquecido con los datos del catalogo, que es lo que consume el frontend.
     *
     * La guia definia ProductoEnriquecidoDTO y nunca lo usaba: devolvia la entidad
     * cruda, sin nombre ni precio, de modo que la interfaz no podia mostrar el
     * producto. Si un itemId no esta en el catalogo se conservan igual rankPosition,
     * score e itemId, y los campos del catalogo quedan vacios, para que la
     * recomendacion no desaparezca de la lista.
     */
    public List<ProductoEnriquecidoDTO> obtenerTop5Enriquecido(String userId) {
        List<ProductoEnriquecidoDTO> salida = new ArrayList<>();

        for (Recomendaciones rec : recomendacionesRepository.findByUserIdOrderByRankPositionAsc(userId)) {
            ProductoEnriquecidoDTO dto = new ProductoEnriquecidoDTO();
            dto.setRankPosition(rec.getRankPosition());
            dto.setScore(rec.getScore());
            dto.setItemId(rec.getItemId());

            Producto p = productCatalog.buscarPorId(rec.getItemId()).orElse(null);
            if (p != null) {
                dto.setNombre(p.getNombre());
                dto.setCategoria(p.getCategoria());
                dto.setMarca(p.getMarca());
                dto.setPrecio(p.getPrecio());
                dto.setCalificacion(p.getCalificacion());
                dto.setImagenUrl(p.getImagenUrl());
            } else {
                // Sin coincidencia en el catalogo: se respeta la categoria guardada
                // al momento de la interaccion para no perder el contexto.
                dto.setCategoria(rec.getCategory());
            }
            salida.add(dto);
        }
        return salida;
    }
}
