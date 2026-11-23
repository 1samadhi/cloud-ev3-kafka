package com.pedidos360.rabbitadmin.Component;

import com.pedidos360.rabbitadmin.Config.ProteccionRecursosProperties;
import com.pedidos360.rabbitadmin.Exception.RecursoProtegidoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProteccionRecursosTest {

    private final ProteccionRecursos proteccion = new ProteccionRecursos(new ProteccionRecursosProperties());

    @ParameterizedTest
    @ValueSource(strings = {"amq.direct", "amq.topic", "amq.fanout", "amq.headers", "amq.match",
            "amq.rabbitmq.trace", "amq.gen-JzTY20BRgKO-HjmUJj0wLg"})
    void todoLoQueEmpiezaConAmqEstaProtegido(String nombre) {
        assertThat(proteccion.esProtegido(nombre)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pedidos360.notificaciones", "pedidos360.notificaciones.dlx",
            "notificaciones.cliente", "notificaciones.auditoria", "notificaciones.cliente.dlq"})
    void laTopologiaDelSistemaEstaProtegida(String nombre) {
        assertThat(proteccion.esProtegido(nombre)).isTrue();
    }

    @Test
    void elExchangePorDefectoEstaProtegido() {
        assertThat(proteccion.esProtegido("")).isTrue();
        assertThat(proteccion.esProtegido(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prueba.classic", "amqp.cola", "amq", "mi-amq.cola", "notificaciones.cliente.copia"})
    void unRecursoComunNoEstaProtegido(String nombre) {
        assertThat(proteccion.esProtegido(nombre)).isFalse();
    }

    @Test
    void sinForzarLanza403ConElMotivoYConForzarDejaPasar() {
        assertThatThrownBy(() -> proteccion.verificarBorrado("el exchange", "amq.topic", false))
                .isInstanceOf(RecursoProtegidoException.class)
                .hasMessageContaining("amq.")
                .hasMessageContaining("forzar=true");
        assertThatCode(() -> proteccion.verificarBorrado("el exchange", "amq.topic", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> proteccion.verificarBorrado("la cola", "prueba.classic", false))
                .doesNotThrowAnyException();
    }

    @Test
    void bindingProtegidoSiTocaLaTopologiaDelSistemaPeroNoSiEsAmqTopicAMano() {
        assertThat(proteccion.esBindingProtegido("pedidos360.notificaciones", "notificaciones.cliente")).isTrue();
        assertThat(proteccion.esBindingProtegido("amq.topic", "prueba.classic")).isFalse();
        assertThatThrownBy(() -> proteccion.verificarBorradoBinding(
                "pedidos360.notificaciones", "notificaciones.cliente", "pedido.*", false))
                .isInstanceOf(RecursoProtegidoException.class);
    }
}
