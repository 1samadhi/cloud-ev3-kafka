package com.pedidos360.notificaciones.Config;

import com.pedidos360.notificaciones.Exception.PayloadInvalidoException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Qué se reintenta y qué no. Las excepciones se envuelven en
 * ListenerExecutionFailedException como lo hace el contenedor real.
 */
class RabbitConfigReintentosTest {

    private final Message mensaje = new Message("{}".getBytes(), new MessageProperties());

    private RetryTemplate plantilla() {
        ReintentosRabbitProperties props = new ReintentosRabbitProperties();
        props.setIntervaloInicialMs(1);
        props.setIntervaloMaximoMs(2);
        return RabbitConfig.crearPlantillaReintentos(props);
    }

    private int ejecutarFallando(RuntimeException causa) {
        AtomicInteger intentos = new AtomicInteger();
        assertThatThrownBy(() -> plantilla().execute(ctx -> {
            intentos.incrementAndGet();
            throw new ListenerExecutionFailedException("fallo del listener", causa, mensaje);
        })).isInstanceOf(ListenerExecutionFailedException.class);
        return intentos.get();
    }

    @Test
    void payloadInvalidoNoSeReintenta() {
        assertThat(ejecutarFallando(new PayloadInvalidoException("sin usuarioId"))).isEqualTo(1);
    }

    @Test
    void jsonRotoNoSeReintenta() {
        assertThat(ejecutarFallando(new MessageConversionException("JSON inválido"))).isEqualTo(1);
    }

    @Test
    void errorTransitorioSeReintentaTresVeces() {
        assertThat(ejecutarFallando(new DataAccessResourceFailureException("MySQL caído"))).isEqualTo(3);
    }

    @Test
    void elRecuperadorRechazaSinReencolar() {
        RabbitConfig.RecuperadorHaciaDlq recuperador = new RabbitConfig.RecuperadorHaciaDlq();
        // RejectAndDontRequeueRecoverer señala al contenedor "no reencolar"
        // envolviendo la causa en AmqpRejectAndDontRequeueException.
        assertThatThrownBy(() -> recuperador.recover(mensaje, new IllegalStateException("x")))
                .isInstanceOf(ListenerExecutionFailedException.class)
                .hasCauseInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    }
}
