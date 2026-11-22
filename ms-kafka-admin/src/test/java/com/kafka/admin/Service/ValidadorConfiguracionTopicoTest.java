package com.kafka.admin.Service;

import com.kafka.admin.Exception.SolicitudInvalidaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas de la lista blanca y del tipado de valores, sin Kafka ni Spring. */
class ValidadorConfiguracionTopicoTest {

    private final ValidadorConfiguracionTopico validador = new ValidadorConfiguracionTopico();

    @Test
    void laListaBlancaEsExactamenteLaDelContrato() {
        assertThat(ValidadorConfiguracionTopico.LISTA_BLANCA).containsExactlyInAnyOrder(
                "retention.ms", "retention.bytes", "cleanup.policy", "min.insync.replicas",
                "segment.bytes", "segment.ms", "compression.type", "max.message.bytes",
                "min.compaction.lag.ms", "min.cleanable.dirty.ratio");
    }

    @Test
    void aceptaLosValoresDeLaTablaDelContratoYNormalizaEspacios() {
        Map<String, String> entrada = new LinkedHashMap<>();
        entrada.put("retention.ms", "-1");
        entrada.put("cleanup.policy", "compact, delete");
        entrada.put("min.insync.replicas", "2");
        entrada.put("segment.bytes", "268435456");
        entrada.put("segment.ms", "3600000");
        entrada.put("compression.type", "lz4");
        entrada.put("min.compaction.lag.ms", "60000");
        entrada.put("min.cleanable.dirty.ratio", "0.1");

        Map<String, String> salida = validador.validarEstablecer(entrada, 3);

        assertThat(salida).containsEntry("cleanup.policy", "compact,delete")
                .containsEntry("retention.ms", "-1")
                .hasSize(8);
    }

    @Test
    void rechazaClavesFueraDeLaListaBlanca() {
        assertThatThrownBy(() -> validador.validarEstablecer(Map.of("unclean.leader.election.enable", "true"), 3))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessageContaining("'unclean.leader.election.enable' no está en la lista blanca");
    }

    @Test
    void minInsyncReplicasNoPuedeSuperarElFactorDeReplicacion() {
        assertThat(validador.validarEstablecer(Map.of("min.insync.replicas", "3"), 3)).hasSize(1);
        assertThatThrownBy(() -> validador.validarEstablecer(Map.of("min.insync.replicas", "4"), 3))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessageContaining("factor de replicación del tópico (3)");
    }

    @ParameterizedTest(name = "{0}={1} se rechaza")
    @CsvSource(delimiter = '|', value = {
            "retention.ms|-2",
            "retention.ms|7d",
            "retention.bytes|abc",
            "cleanup.policy|borrar",
            "cleanup.policy|delete,compact,delete",
            "min.insync.replicas|0",
            "segment.bytes|1024",
            "segment.bytes|3000000000",
            "segment.ms|0",
            "compression.type|brotli",
            "max.message.bytes|0",
            "min.compaction.lag.ms|-1",
            "min.cleanable.dirty.ratio|1.5",
            "min.cleanable.dirty.ratio|mucho"
    })
    void rechazaValoresMalTipadosOFueraDeRango(String clave, String valor) {
        assertThatThrownBy(() -> validador.validarEstablecer(Map.of(clave, valor), 3))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessageContaining(clave);
    }

    @Test
    void reuneTodosLosErroresEnUnSoloMensaje() {
        Map<String, String> entrada = new LinkedHashMap<>();
        entrada.put("retention.ms", "x");
        entrada.put("compression.type", "brotli");
        entrada.put("foo", "1");
        assertThatThrownBy(() -> validador.validarEstablecer(entrada, 3))
                .hasMessageContaining("retention.ms")
                .hasMessageContaining("compression.type")
                .hasMessageContaining("'foo'");
    }

    @Test
    void restablecerSoloAdmiteClavesDeLaListaBlancaSinRepetir() {
        validador.validarRestablecer(List.of("retention.ms", "segment.ms"));
        assertThatThrownBy(() -> validador.validarRestablecer(List.of("log.dirs")))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> validador.validarRestablecer(List.of("retention.ms", "retention.ms")))
                .hasMessageContaining("repetida");
    }

    @Test
    void valorVacioSeRechazaYSeSugiereRestablecer() {
        Map<String, String> entrada = new LinkedHashMap<>();
        entrada.put("retention.ms", "");
        assertThatThrownBy(() -> validador.validarEstablecer(entrada, 3))
                .hasMessageContaining("restablecer");
    }
}
