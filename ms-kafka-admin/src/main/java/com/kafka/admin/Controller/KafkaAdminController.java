package com.kafka.admin.Controller;

import com.kafka.admin.DTO.*;
import com.kafka.admin.Service.KafkaAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST para la administración de Kafka.
 *
 * Sólo traduce HTTP <-> servicio: las reglas (lista blanca, "sólo aumentar",
 * grupo vacío) viven en el servicio para que también apliquen si otro
 * componente lo invoca. Los errores los convierte GlobalExceptionHandler.
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
        return ResponseEntity.ok(kafkaAdminService.listarTopicos());
    }

    /**
     * GET /api/topicos/{nombre}
     * Obtiene el detalle completo de un tópico específico.
     */
    @GetMapping("/topicos/{nombre}")
    public ResponseEntity<TopicoDetalleDTO> obtenerTopicoDetalle(@PathVariable String nombre) {
        return ResponseEntity.ok(kafkaAdminService.obtenerTopicoDetalle(nombre));
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
     * GET /api/topicos/{nombre}/configuracion
     * Configuración completa del tópico, marcando qué está sobrescrito.
     */
    @GetMapping("/topicos/{nombre}/configuracion")
    public ResponseEntity<ConfiguracionRecursoDTO> obtenerConfiguracionTopico(@PathVariable String nombre) {
        return ResponseEntity.ok(kafkaAdminService.obtenerConfiguracionTopico(nombre));
    }

    /**
     * PUT /api/topicos/{nombre}/configuracion
     * Cambia (SET) o devuelve al valor heredado (DELETE) claves de la lista blanca.
     */
    @PutMapping("/topicos/{nombre}/configuracion")
    public ResponseEntity<ConfiguracionRecursoDTO> modificarConfiguracionTopico(
            @PathVariable String nombre, @RequestBody ModificarConfiguracionRequestDTO request) {
        return ResponseEntity.ok(kafkaAdminService.modificarConfiguracionTopico(nombre, request));
    }

    /**
     * POST /api/topicos/{nombre}/particiones
     * Aumenta las particiones a la cantidad total indicada (nunca reduce).
     */
    @PostMapping("/topicos/{nombre}/particiones")
    public ResponseEntity<ResultadoParticionesDTO> aumentarParticiones(
            @PathVariable String nombre, @Valid @RequestBody AumentarParticionesRequestDTO request) {
        return ResponseEntity.ok(kafkaAdminService.aumentarParticiones(nombre, request.getParticiones()));
    }

    /**
     * GET /api/consumer-groups
     * Lista todos los consumer groups del cluster.
     */
    @GetMapping("/consumer-groups")
    public ResponseEntity<List<ConsumerGroupDTO>> listarConsumerGroups() {
        return ResponseEntity.ok(kafkaAdminService.listarConsumerGroups());
    }

    /**
     * GET /api/consumer-groups/{id}
     * Estado, coordinador, estrategia y particiones asignadas a cada miembro.
     */
    @GetMapping("/consumer-groups/{id}")
    public ResponseEntity<ConsumerGroupDetalleDTO> obtenerConsumerGroupDetalle(@PathVariable String id) {
        return ResponseEntity.ok(kafkaAdminService.obtenerConsumerGroupDetalle(id));
    }

    /**
     * GET /api/consumer-groups/{id}/lag
     * Obtiene el lag detallado de un consumer group específico.
     */
    @GetMapping("/consumer-groups/{id}/lag")
    public ResponseEntity<ConsumerGroupLagDTO> obtenerConsumerGroupLag(@PathVariable String id) {
        return ResponseEntity.ok(kafkaAdminService.obtenerConsumerGroupLag(id));
    }

    /**
     * POST /api/consumer-groups/{id}/offsets/reset
     * Mueve los offsets del grupo (EARLIEST, LATEST, TIMESTAMP u OFFSET). 409 si tiene miembros.
     */
    @PostMapping("/consumer-groups/{id}/offsets/reset")
    public ResponseEntity<ResultadoResetOffsetsDTO> resetearOffsets(
            @PathVariable String id, @Valid @RequestBody ResetOffsetsRequestDTO request) {
        return ResponseEntity.ok(kafkaAdminService.resetearOffsets(id, request));
    }

    /**
     * DELETE /api/consumer-groups/{id}
     * Elimina un grupo vacío (y con él sus offsets). 409 si tiene miembros.
     */
    @DeleteMapping("/consumer-groups/{id}")
    public ResponseEntity<String> eliminarConsumerGroup(@PathVariable String id) {
        kafkaAdminService.eliminarConsumerGroup(id);
        return ResponseEntity.ok("Consumer group '" + id + "' eliminado exitosamente");
    }

    /**
     * GET /api/cluster
     * Obtiene el resumen del cluster: brokers y controlador.
     */
    @GetMapping("/cluster")
    public ResponseEntity<ClusterDTO> obtenerCluster() {
        return ResponseEntity.ok(kafkaAdminService.obtenerCluster());
    }

    /**
     * GET /api/brokers/{id}/configuracion
     * Configuración de un broker, marcando qué difiere del valor de fábrica.
     */
    @GetMapping("/brokers/{id}/configuracion")
    public ResponseEntity<ConfiguracionRecursoDTO> obtenerConfiguracionBroker(@PathVariable int id) {
        return ResponseEntity.ok(kafkaAdminService.obtenerConfiguracionBroker(id));
    }
}
