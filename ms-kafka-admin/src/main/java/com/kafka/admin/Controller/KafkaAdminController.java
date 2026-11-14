package com.kafka.admin.Controller;

import com.kafka.admin.DTO.*;
import com.kafka.admin.Service.KafkaAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST para la administración de Kafka.
 * Todos los endpoints están protegidos contra CORS para permitir acceso desde el frontend local.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KafkaAdminController {

    private final KafkaAdminService kafkaAdminService;

    /**
     * GET /api/topicos
     * Lista todos los tópicos del cluster, excluyendo los internos.
     */
    @GetMapping("/topicos")
    public ResponseEntity<List<TopicoDTO>> listarTopicos() {
        List<TopicoDTO> topicos = kafkaAdminService.listarTopicos();
        return ResponseEntity.ok(topicos);
    }

    /**
     * GET /api/topicos/{nombre}
     * Obtiene el detalle completo de un tópico específico.
     */
    @GetMapping("/topicos/{nombre}")
    public ResponseEntity<TopicoDetalleDTO> obtenerTopicoDetalle(@PathVariable String nombre) {
        TopicoDetalleDTO detalle = kafkaAdminService.obtenerTopicoDetalle(nombre);
        return ResponseEntity.ok(detalle);
    }

    /**
     * POST /api/topicos
     * Crea un nuevo tópico con validaciones exhaustivas.
     */
    @PostMapping("/topicos")
    public ResponseEntity<String> crearTopico(@RequestBody CrearTopicoRequestDTO request) {
        kafkaAdminService.crearTopico(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Tópico '" + request.getNombre() + "' creado exitosamente");
    }

    /**
     * DELETE /api/topicos/{nombre}
     * Elimina un tópico. Rechaza tópicos internos y protegidos.
     */
    @DeleteMapping("/topicos/{nombre}")
    public ResponseEntity<String> eliminarTopico(@PathVariable String nombre) {
        kafkaAdminService.eliminarTopico(nombre);
        return ResponseEntity.ok("Tópico '" + nombre + "' eliminado exitosamente");
    }

    /**
     * GET /api/consumer-groups
     * Lista todos los consumer groups del cluster.
     */
    @GetMapping("/consumer-groups")
    public ResponseEntity<List<ConsumerGroupDTO>> listarConsumerGroups() {
        List<ConsumerGroupDTO> groups = kafkaAdminService.listarConsumerGroups();
        return ResponseEntity.ok(groups);
    }

    /**
     * GET /api/consumer-groups/{id}/lag
     * Obtiene el lag detallado de un consumer group específico.
     */
    @GetMapping("/consumer-groups/{id}/lag")
    public ResponseEntity<ConsumerGroupLagDTO> obtenerConsumerGroupLag(@PathVariable String id) {
        ConsumerGroupLagDTO lag = kafkaAdminService.obtenerConsumerGroupLag(id);
        return ResponseEntity.ok(lag);
    }

    /**
     * GET /api/cluster
     * Obtiene el resumen del cluster: brokers y controlador.
     */
    @GetMapping("/cluster")
    public ResponseEntity<ClusterDTO> obtenerCluster() {
        ClusterDTO cluster = kafkaAdminService.obtenerCluster();
        return ResponseEntity.ok(cluster);
    }
}
