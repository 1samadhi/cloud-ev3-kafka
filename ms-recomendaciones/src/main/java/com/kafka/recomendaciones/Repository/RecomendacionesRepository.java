package com.kafka.recomendaciones.Repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kafka.recomendaciones.Model.Recomendaciones;

import java.util.List;

public interface RecomendacionesRepository extends JpaRepository<Recomendaciones, Long> {
    List<Recomendaciones> findByUserIdOrderByRankPositionAsc(String userId);
    void deleteByUserId(String userId);
}
