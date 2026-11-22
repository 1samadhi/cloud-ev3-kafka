package com.pedidos360.notificaciones.Service;

import com.pedidos360.notificaciones.DTO.NotificacionDTO;
import com.pedidos360.notificaciones.DTO.NotificacionRespuestaDTO;
import com.pedidos360.notificaciones.Repository.NotificacionRepositorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Servicio real + JPA real sobre H2; el EmisorSse es un spy para verificar la emisión. */
@SpringBootTest
class ServicioNotificacionesIntegracionTest {

    @Autowired
    private ServicioNotificaciones servicio;

    @Autowired
    private NotificacionRepositorio repositorio;

    @MockitoSpyBean
    private EmisorSse emisorSse;

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
        clearInvocations(emisorSse);
    }

    private static NotificacionDTO dto(String usuarioId, long timestamp) {
        return NotificacionDTO.builder()
                .idNotificacion(UUID.randomUUID().toString())
                .usuarioId(usuarioId)
                .tipo("pedido.creado")
                .titulo("Pedido creado")
                .mensaje("Recibimos tu pedido")
                .idPedido("P-7")
                .datos(Map.of("total", 59990, "items", 2))
                .timestamp(timestamp)
                .build();
    }

    @Test
    void persisteYEmitePorSse() {
        NotificacionDTO entrada = dto("u-sse", 1_700_000_000_000L);

        Optional<NotificacionRespuestaDTO> resultado = servicio.procesar(entrada);

        assertThat(resultado).isPresent();
        var guardada = repositorio.findById(entrada.getIdNotificacion()).orElseThrow();
        assertThat(guardada.getUsuarioId()).isEqualTo("u-sse");
        assertThat(guardada.getIdPedido()).isEqualTo("P-7");
        assertThat(guardada.isLeida()).isFalse();
        assertThat(guardada.getDatos()).containsEntry("total", 59990);
        assertThat(guardada.getCreadaEn().toEpochMilli()).isEqualTo(1_700_000_000_000L);

        ArgumentCaptor<NotificacionRespuestaDTO> captor = ArgumentCaptor.forClass(NotificacionRespuestaDTO.class);
        verify(emisorSse).emitir(eq("u-sse"), captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(entrada.getIdNotificacion());
        assertThat(captor.getValue().titulo()).isEqualTo("Pedido creado");
    }

    @Test
    void elDuplicadoSeIgnoraYNoSeEmiteDosVeces() {
        NotificacionDTO entrada = dto("u-dup", System.currentTimeMillis());

        assertThat(servicio.procesar(entrada)).isPresent();
        assertThat(servicio.procesar(entrada)).isEmpty();

        assertThat(repositorio.count()).isEqualTo(1);
        verify(emisorSse).emitir(eq("u-dup"), any());
    }

    @Test
    void listaMasRecientesPrimeroYMarcaLeidas() {
        servicio.procesar(dto("u-lista", 1_000L));
        servicio.procesar(dto("u-lista", 3_000L));
        servicio.procesar(dto("u-lista", 2_000L));
        servicio.procesar(dto("otro", 4_000L));

        var lista = servicio.listar("u-lista");
        assertThat(lista).extracting(n -> n.creadaEn().toEpochMilli()).containsExactly(3_000L, 2_000L, 1_000L);

        assertThat(servicio.marcarLeida(lista.get(0).id()).leida()).isTrue();
        assertThat(servicio.marcarTodasLeidas("u-lista")).isEqualTo(2);
        assertThat(servicio.listar("u-lista")).allMatch(NotificacionRespuestaDTO::leida);
        assertThat(servicio.listar("otro")).noneMatch(NotificacionRespuestaDTO::leida);
    }

    @Test
    void sinTimestampUsaLaHoraDeRecepcion() {
        NotificacionDTO entrada = dto("u-ts", 0);
        entrada.setTimestamp(null);
        servicio.procesar(entrada);
        assertThat(repositorio.findById(entrada.getIdNotificacion()).orElseThrow().getCreadaEn()).isNotNull();
        verify(emisorSse, never()).emitir(eq("nadie"), any());
        verify(emisorSse).emitir(anyString(), any());
    }
}
