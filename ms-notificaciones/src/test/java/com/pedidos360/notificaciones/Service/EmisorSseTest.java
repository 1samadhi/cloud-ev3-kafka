package com.pedidos360.notificaciones.Service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class EmisorSseTest {

    @Test
    void registraVariasConexionesPorUsuarioYLasQuitaAlCompletar() {
        EmisorSse emisor = new EmisorSse(60_000);

        SseEmitter pestana1 = emisor.suscribir("u-1");
        emisor.suscribir("u-1");
        emisor.suscribir("u-2");

        assertThat(emisor.conexionesActivas("u-1")).isEqualTo(2);
        assertThat(emisor.conexionesActivas("u-2")).isEqualTo(1);
        assertThat(emisor.conexionesActivas("nadie")).isZero();
        assertThat(pestana1).isNotNull();
    }
}
