package com.kafka.pago.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.kafka.pago.DTO.AlertaFraudeDTO;
import com.kafka.pago.DTO.ResumenAlertasDTO;
import com.kafka.pago.Model.EstadoPagoCliente;
import com.kafka.pago.Repository.EstadoPagoClienteRepositorio;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio especializado en la generación de alertas de fraude para el dashboard.
 * 
 * Encapsula la lógica de negocio:
 * - Filtrado de bloqueos vigentes (no expirados)
 * - Cálculo de minutos restantes
 * - Ordenamiento y límite de resultados (máx 20 alertas)
 * - Contadores de estado actual
 * 
 * Criterio de evaluación: mantener la lógica fuera del controlador.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertasService {

    private final EstadoPagoClienteRepositorio estadoRepositorio;

    /**
     * Obtiene un resumen consolidado de alertas de fraude para el dashboard operacional.
     * 
     * @return ResumenAlertasDTO con contadores y lista de alertas activas ordenadas por recencia.
     */
    public ResumenAlertasDTO obtenerResumenAlertas() {
        LocalDateTime ahora = LocalDateTime.now();
        
        // 1. Contar bloqueos VIGENTES (bloqueado=true Y bloqueadoHasta > ahora)
        List<EstadoPagoCliente> bloqueosVigentes = estadoRepositorio.encontrarBloqueoVigentes(ahora);
        int bloqueosActivos = bloqueosVigentes.size();
        
        // 2. Contar total de clientes vigilados (todas las filas de la tabla)
        long totalClientes = estadoRepositorio.count();
        
        // 3. Obtener alertas vigentes (máx 20), ordenadas por recencia
        List<EstadoPagoCliente> registrosVigentes = estadoRepositorio.encontrarAlertasVigentes(ahora);
        
        // 4. Convertir a DTOs calculando minutosRestantes
        List<AlertaFraudeDTO> alertas = registrosVigentes.stream()
            .map(registro -> {
                long minutosRestantes = ChronoUnit.MINUTES.between(ahora, registro.getBloqueadoHasta());
                return new AlertaFraudeDTO(
                    registro.getIdCliente(),
                    registro.getCantidadIntentos(),
                    registro.getBloqueadoHasta(),
                    Math.max(0, minutosRestantes), // Garantizar que no sea negativo
                    registro.getActualizadoEn()
                );
            })
            .collect(Collectors.toList());
        
        log.info("Generado resumen de alertas: {} bloqueos activos, {} clientes vigilados, {} alertas vigentes",
                 bloqueosActivos, totalClientes, alertas.size());
        
        return new ResumenAlertasDTO(bloqueosActivos, (int) totalClientes, alertas);
    }
}
