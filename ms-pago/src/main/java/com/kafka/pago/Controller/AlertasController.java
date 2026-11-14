package com.kafka.pago.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.kafka.pago.DTO.ResumenAlertasDTO;
import com.kafka.pago.Service.AlertasService;

/**
 * Controlador REST para consultar alertas de fraude en tiempo real.
 * Endpoint: GET /api/alertas
 * 
 * Devuelve un resumen estructurado de bloqueos activos y cliente monitoreados
 * para alimentar el dashboard operacional de la tienda.
 */
@Slf4j
@RestController
@RequestMapping("/api/alertas")
@RequiredArgsConstructor
public class AlertasController {

    private final AlertasService alertasService;

    /**
     * GET /api/alertas
     * 
     * Obtiene el resumen actual de alertas de fraude.
     * Si no hay bloqueos, devuelve estructura con listas vacías y ceros.
     * 
     * @return ResumenAlertasDTO con bloqueosActivos, totalClientesVigilados y lista de alertas.
     */
    @GetMapping
    public ResponseEntity<ResumenAlertasDTO> obtenerAlertas() {
        log.info("Consultando resumen de alertas de fraude");
        ResumenAlertasDTO resumen = alertasService.obtenerResumenAlertas();
        return ResponseEntity.ok(resumen);
    }
}
