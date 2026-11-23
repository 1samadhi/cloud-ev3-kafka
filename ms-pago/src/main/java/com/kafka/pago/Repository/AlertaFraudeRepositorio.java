package com.kafka.pago.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kafka.pago.Model.AlertaFraude;

@Repository
public interface AlertaFraudeRepositorio extends JpaRepository<AlertaFraude, Long> {

    List<AlertaFraude> findByHuellaOrderByCreadaEnDesc(String huella);
}
