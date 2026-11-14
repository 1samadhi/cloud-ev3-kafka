package com.kafka.pago.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.kafka.pago.Model.EstadoPagoCliente;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EstadoPagoClienteRepositorio extends JpaRepository<EstadoPagoCliente, String> {
    
    /**
     * Encuentra todos los clientes bloqueados cuyo bloqueo sigue vigente (bloqueadoHasta > ahora).
     * Usado para contar bloqueos activos en tiempo real.
     */
    @Query("SELECT e FROM EstadoPagoCliente e WHERE e.bloqueado = true AND e.bloqueadoHasta > :ahora")
    List<EstadoPagoCliente> encontrarBloqueoVigentes(@Param("ahora") LocalDateTime ahora);
    
    /**
     * Encuentra todos los clientes bloqueados vigentes, ordenados por actualizadoEn descendente.
     * Devuelve máximo 20 resultados para el dashboard.
     * Logica: bloqueado = true AND bloqueadoHasta > ahora, ordenado por timestamp reciente.
     */
    @Query(value = "SELECT * FROM estado_pago_cliente " +
                   "WHERE bloqueado = 1 AND bloqueado_hasta > :ahora " +
                   "ORDER BY actualizado_en DESC LIMIT 20", 
           nativeQuery = true)
    List<EstadoPagoCliente> encontrarAlertasVigentes(@Param("ahora") LocalDateTime ahora);
}
