package com.kafka.recomendaciones.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.kafka.recomendaciones.Model.Interacciones;

import java.util.List;

@Repository
public interface InteraccionesRepository extends JpaRepository<Interacciones, Long> {
    List<Interacciones> findByUserId(String userId);
    
    /**
     * Obtiene los 5 productos con más interacciones, ordenados por cantidad descendente.
     * Agrupado por item_id y contando. Usa consulta nativa SQL.
     */
    @Query(value = "SELECT item_id as itemId, COUNT(*) as count " +
                   "FROM interacciones " +
                   "GROUP BY item_id " +
                   "ORDER BY COUNT(*) DESC", 
           nativeQuery = true)
    List<Object> findTop5ProductsByInteractionCount();
    
    /**
     * Cuenta interacciones dentro de la ventana de tiempo (últimos N milisegundos).
     * @param fromTimestamp timestamp mínimo en milisegundos (System.currentTimeMillis() - ventana)
     */
    @Query("SELECT COUNT(i) FROM Interacciones i WHERE i.timestamp >= :fromTimestamp")
    long countInteraccionesPorVentana(@Param("fromTimestamp") long fromTimestamp);
    
    /**
     * Cuenta usuarios distintos (user_id) con interacciones en la ventana de tiempo.
     * @param fromTimestamp timestamp mínimo en milisegundos
     */
    @Query("SELECT COUNT(DISTINCT i.userId) FROM Interacciones i WHERE i.timestamp >= :fromTimestamp")
    long countUsuariosActivosPorVentana(@Param("fromTimestamp") long fromTimestamp);
}
