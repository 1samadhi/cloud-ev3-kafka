package com.pedidos360.notificaciones.Config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.ImmediateRequeueMessageRecoverer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuración EXPLÍCITA del consumidor RabbitMQ.
 *
 * No se usa spring.rabbitmq.listener.simple.retry.enabled porque esa política
 * reintenta TODO, incluido un JSON roto o un NotificacionDTO sin usuarioId:
 * reintentar algo que no tiene arreglo sólo retrasa su llegada a la DLQ y llena
 * el log. Acá la política clasifica:
 *
 *   - AmqpRejectAndDontRequeueException / MessageConversionException
 *       -> NO se reintenta: rechazo inmediato -> DLX -> DLQ.
 *   - cualquier otra (base caída, timeout)  -> 3 intentos con backoff 1 s x2;
 *       si se agotan, RejectAndDontRequeueRecoverer -> DLX -> DLQ.
 *
 * Hay tres fábricas porque hay tres necesidades:
 *   - rabbitListenerContainerFactory (por defecto): JSON -> NotificacionDTO.
 *   - fabricaMensajesCrudos: auditoría; recibe el Message tal cual (no debe
 *       fallar por no poder convertir: audita también lo que viene mal).
 *   - fabricaDlq: la DLQ es el final del camino. Si falla guardar el mensaje
 *       muerto (base caída) se REENCOLA en vez de descartarlo.
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /**
     * JSON con el ObjectMapper de Spring Boot (ignora propiedades desconocidas:
     * ms-carrito puede agregar campos sin romper a este consumidor). El tipo
     * destino se infiere del parámetro del @RabbitListener, no del header
     * __TypeId__ del productor: cada servicio tiene su propia copia del DTO.
     */
    @Bean
    public Jackson2JsonMessageConverter conversorJson(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ReintentosRabbitProperties reintentos) {
        SimpleRabbitListenerContainerFactory fabrica = new SimpleRabbitListenerContainerFactory();
        // El configurer aplica lo de application.yaml (ack auto, prefetch,
        // concurrencia, auto-startup) y el conversor JSON de arriba.
        configurer.configure(fabrica, connectionFactory);
        fabrica.setDefaultRequeueRejected(false);
        fabrica.setAdviceChain(crearInterceptorReintentos(reintentos, new RecuperadorHaciaDlq()));
        return fabrica;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory fabricaMensajesCrudos(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ReintentosRabbitProperties reintentos) {
        SimpleRabbitListenerContainerFactory fabrica = new SimpleRabbitListenerContainerFactory();
        configurer.configure(fabrica, connectionFactory);
        // SimpleMessageConverter nunca falla: devuelve byte[]/String. El
        // listener lee el Message crudo.
        fabrica.setMessageConverter(new SimpleMessageConverter());
        fabrica.setDefaultRequeueRejected(false);
        fabrica.setAdviceChain(crearInterceptorReintentos(reintentos, new RecuperadorHaciaDlq()));
        return fabrica;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory fabricaDlq(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ReintentosRabbitProperties reintentos) {
        SimpleRabbitListenerContainerFactory fabrica = new SimpleRabbitListenerContainerFactory();
        configurer.configure(fabrica, connectionFactory);
        fabrica.setMessageConverter(new SimpleMessageConverter());
        // La DLQ no tiene DLX: rechazar sin reencolar BORRARÍA la evidencia.
        // Si no se pudo registrar, el mensaje vuelve a la DLQ y se reintenta.
        fabrica.setDefaultRequeueRejected(true);
        fabrica.setAdviceChain(crearInterceptorReintentos(reintentos, new RecuperadorReencolarDlq()));
        return fabrica;
    }

    /** Interceptor stateless: reintenta en el mismo hilo, sin devolver el mensaje al broker. */
    public static RetryOperationsInterceptor crearInterceptorReintentos(ReintentosRabbitProperties props,
                                                                        MessageRecoverer recuperador) {
        return RetryInterceptorBuilder.stateless()
                .retryOperations(crearPlantillaReintentos(props))
                .recoverer(recuperador)
                .build();
    }

    /**
     * Público y estático para probar la clasificación (qué se reintenta y qué
     * no) sin levantar un broker.
     */
    public static RetryTemplate crearPlantillaReintentos(ReintentosRabbitProperties props) {
        Map<Class<? extends Throwable>, Boolean> reintentables = new HashMap<>();
        // false = no reintentar. Spring envuelve la excepción del listener en
        // ListenerExecutionFailedException; traverseCauses=true busca estas en
        // la cadena de causas.
        reintentables.put(AmqpRejectAndDontRequeueException.class, false);
        reintentables.put(MessageConversionException.class, false);
        reintentables.put(org.springframework.messaging.converter.MessageConversionException.class, false);
        reintentables.put(org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException.class, false);
        SimpleRetryPolicy politica = new SimpleRetryPolicy(props.getMaxIntentos(), reintentables, true, true);

        ExponentialBackOffPolicy espera = new ExponentialBackOffPolicy();
        espera.setInitialInterval(props.getIntervaloInicialMs());
        espera.setMultiplier(props.getMultiplicador());
        espera.setMaxInterval(props.getIntervaloMaximoMs());

        RetryTemplate plantilla = new RetryTemplate();
        plantilla.setRetryPolicy(politica);
        plantilla.setBackOffPolicy(espera);
        plantilla.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext contexto, RetryCallback<T, E> callback,
                                                         Throwable error) {
                Throwable causa = causaRaiz(error);
                log.warn("Fallo procesando mensaje RabbitMQ (intento {}/{}): {}: {}",
                        contexto.getRetryCount(), props.getMaxIntentos(),
                        causa.getClass().getSimpleName(), causa.getMessage());
            }
        });
        return plantilla;
    }

    static Throwable causaRaiz(Throwable error) {
        Throwable actual = error;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual;
    }

    /** Log en español con contexto antes de rechazar (el de Spring es genérico). */
    static class RecuperadorHaciaDlq extends RejectAndDontRequeueRecoverer {
        @Override
        public void recover(Message mensaje, Throwable causa) {
            Throwable raiz = causaRaiz(causa);
            log.error("Mensaje rechazado SIN reencolar -> DLX -> DLQ: cola={} routingKey={} messageId={} motivo={}: {}",
                    mensaje.getMessageProperties().getConsumerQueue(),
                    mensaje.getMessageProperties().getReceivedRoutingKey(),
                    mensaje.getMessageProperties().getMessageId(),
                    raiz.getClass().getSimpleName(), raiz.getMessage());
            super.recover(mensaje, causa);
        }
    }

    static class RecuperadorReencolarDlq extends ImmediateRequeueMessageRecoverer {
        @Override
        public void recover(Message mensaje, Throwable causa) {
            Throwable raiz = causaRaiz(causa);
            log.error("No se pudo registrar el mensaje muerto messageId={}; se devuelve a la DLQ para no perderlo: {}: {}",
                    mensaje.getMessageProperties().getMessageId(), raiz.getClass().getSimpleName(), raiz.getMessage());
            super.recover(mensaje, causa);
        }
    }
}
