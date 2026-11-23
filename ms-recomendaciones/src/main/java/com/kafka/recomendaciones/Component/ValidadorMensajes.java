package com.kafka.recomendaciones.Component;

import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validacion jakarta comun a los listeners. Lanza PayloadInvalidoException, que
 * el DefaultErrorHandler marca como NO reintentable: un DTO invalido va directo
 * al DLT en vez de gastar tres reintentos que no lo van a arreglar.
 */
@Component
public class ValidadorMensajes {

    private final Validator validador;

    public ValidadorMensajes(Validator validador) {
        this.validador = validador;
    }

    public <T> T validar(ConsumerRecord<String, T> registro, String nombreDto) {
        T dto = registro.value();
        if (dto == null) {
            throw new PayloadInvalidoException(nombreDto + " sin cuerpo en " + registro.topic()
                    + "-" + registro.partition() + "@" + registro.offset());
        }
        Set<ConstraintViolation<T>> violaciones = validador.validate(dto);
        if (!violaciones.isEmpty()) {
            String detalle = violaciones.stream().map(ConstraintViolation::getMessage).sorted()
                    .collect(Collectors.joining("; "));
            throw new PayloadInvalidoException(nombreDto + " invalido: " + detalle);
        }
        return dto;
    }
}
