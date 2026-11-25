package com.pedidos360.rabbitadmin.Service;

import com.pedidos360.rabbitadmin.Component.ClienteManagementRabbit;
import com.pedidos360.rabbitadmin.Component.ProteccionRecursos;
import com.pedidos360.rabbitadmin.DTO.BindingDTO;
import com.pedidos360.rabbitadmin.DTO.BindingRequestDTO;
import com.pedidos360.rabbitadmin.DTO.ColaDTO;
import com.pedidos360.rabbitadmin.DTO.CrearColaRequestDTO;
import com.pedidos360.rabbitadmin.DTO.CrearExchangeRequestDTO;
import com.pedidos360.rabbitadmin.DTO.EstadoClusterDTO;
import com.pedidos360.rabbitadmin.DTO.ExchangeDTO;
import com.pedidos360.rabbitadmin.DTO.NodoDTO;
import com.pedidos360.rabbitadmin.DTO.RespuestaOperacionDTO;
import com.pedidos360.rabbitadmin.Exception.ConflictoException;
import com.pedidos360.rabbitadmin.Exception.RecursoNoEncontradoException;
import com.pedidos360.rabbitadmin.Exception.RecursoProtegidoException;
import com.pedidos360.rabbitadmin.Exception.RecursoYaExisteException;
import com.pedidos360.rabbitadmin.Model.BindingApi;
import com.pedidos360.rabbitadmin.Model.ColaApi;
import com.pedidos360.rabbitadmin.Model.ExchangeApi;
import com.pedidos360.rabbitadmin.Model.NodoApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reglas de administración: protección de recursos, existencia previa y
 * traducción de pedidos de la consola a operaciones de AmqpAdmin.
 *
 * Escribe por AMQP (AmqpAdmin) y lee por la API de management: AMQP no sabe
 * enumerar recursos, y la API de management no es la vía con la que los
 * servicios declaran su topología.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioRabbitAdmin {

    private static final String COLA = "la cola";
    private static final String EXCHANGE = "el exchange";

    private final AmqpAdmin amqpAdmin;
    private final ClienteManagementRabbit management;
    private final ProteccionRecursos proteccion;

    // ------------------------------------------------------------------ colas

    public List<ColaDTO> listarColas(boolean soloConMensajes) {
        return management.listarColas().stream()
                .map(this::aDto)
                .filter(c -> !soloConMensajes || (c.mensajes() != null && c.mensajes() > 0))
                .sorted(Comparator.comparing(ColaDTO::nombre))
                .toList();
    }

    public RespuestaOperacionDTO crearCola(CrearColaRequestDTO req) {
        String nombre = req.nombre();
        // Redeclarar una cola idéntica es inocuo en AMQP y no avisaría nada; con
        // argumentos distintos el broker cierra el canal (PRECONDITION_FAILED).
        // Preguntar antes da un 409 claro en los dos casos.
        if (amqpAdmin.getQueueInfo(nombre) != null) {
            throw new RecursoYaExisteException("La cola '" + nombre + "' ya existe");
        }

        QueueBuilder builder = Boolean.TRUE.equals(req.durable())
                ? QueueBuilder.durable(nombre)
                : QueueBuilder.nonDurable(nombre);
        if (req.esQuorum()) {
            builder.quorum();
            if (req.limiteEntregas() != null) {
                builder.deliveryLimit(req.limiteEntregas());
            }
        } else {
            // Explícito aunque classic sea el default: una política
            // default-queue-type del vhost podría convertirla en otra cosa.
            builder.withArgument("x-queue-type", "classic");
        }
        if (req.deadLetterExchange() != null) {
            builder.deadLetterExchange(req.deadLetterExchange());
        }
        if (req.ttlMensajesMs() != null) {
            builder.ttl(req.ttlMensajesMs());
        }
        if (req.longitudMaxima() != null) {
            builder.maxLength(req.longitudMaxima());
        }
        Queue cola = builder.build();
        amqpAdmin.declareQueue(cola);
        log.info("Cola creada: nombre={} tipo={} durable={} argumentos={}",
                nombre, req.tipo(), cola.isDurable(), cola.getArguments());

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", req.tipo());
        detalle.put("durable", cola.isDurable());
        detalle.put("argumentos", cola.getArguments());
        return RespuestaOperacionDTO.de("Cola creada", "cola", nombre, detalle);
    }

    public RespuestaOperacionDTO borrarCola(String nombre, boolean siVacia, boolean forzar) {
        // La protección va ANTES de tocar el broker: un recurso protegido no se
        // consulta ni se intenta borrar si no viene forzar=true.
        proteccion.verificarBorrado(COLA, nombre, forzar);

        QueueInformation info = amqpAdmin.getQueueInfo(nombre);
        if (info == null) {
            throw new RecursoNoEncontradoException("La cola '" + nombre + "' no existe");
        }

        if (siVacia) {
            // Chequeo propio para dar un 409 explicativo; además se pide
            // if-empty al broker, que es el que decide de forma atómica si entre
            // la consulta y el borrado llegó un mensaje.
            if (info.getMessageCount() > 0) {
                throw new ConflictoException("La cola '" + nombre + "' tiene " + info.getMessageCount()
                        + " mensajes listos y se pidió siVacia=true: no se borra");
            }
            amqpAdmin.deleteQueue(nombre, false, true);
        } else if (!amqpAdmin.deleteQueue(nombre)) {
            throw new ConflictoException("RabbitMQ rechazó el borrado de la cola '" + nombre
                    + "' (p. ej. es exclusiva de otra conexión)");
        }
        log.info("Cola borrada: nombre={} siVacia={} forzar={} mensajesDescartados={}",
                nombre, siVacia, forzar, info.getMessageCount());

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("mensajesDescartados", info.getMessageCount());
        detalle.put("siVacia", siVacia);
        detalle.put("forzado", forzar && proteccion.esProtegido(nombre));
        return RespuestaOperacionDTO.de("Cola borrada", "cola", nombre, detalle);
    }

    public RespuestaOperacionDTO purgarCola(String nombre) {
        if (amqpAdmin.getQueueInfo(nombre) == null) {
            throw new RecursoNoEncontradoException("La cola '" + nombre + "' no existe");
        }
        int eliminados = amqpAdmin.purgeQueue(nombre);
        log.info("Cola purgada: nombre={} mensajesEliminados={}", nombre, eliminados);
        return RespuestaOperacionDTO.de("Cola purgada", "cola", nombre, Map.of("mensajesEliminados", eliminados));
    }

    // -------------------------------------------------------------- exchanges

    public List<ExchangeDTO> listarExchanges() {
        return management.listarExchanges().stream()
                .map(this::aDto)
                .sorted(Comparator.comparing(ExchangeDTO::nombre))
                .toList();
    }

    public RespuestaOperacionDTO crearExchange(CrearExchangeRequestDTO req) {
        String nombre = req.nombre();
        if (management.existeExchange(nombre)) {
            throw new RecursoYaExisteException("El exchange '" + nombre + "' ya existe");
        }
        ExchangeBuilder builder = new ExchangeBuilder(nombre, req.tipo()).durable(req.durable());
        if (req.autoEliminar()) {
            builder.autoDelete();
        }
        if (req.interno()) {
            builder.internal();
        }
        if (req.exchangeAlterno() != null) {
            builder.alternate(req.exchangeAlterno());
        }
        Exchange exchange = builder.build();
        amqpAdmin.declareExchange(exchange);
        log.info("Exchange creado: nombre={} tipo={} durable={}", nombre, req.tipo(), req.durable());

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", exchange.getType());
        detalle.put("durable", exchange.isDurable());
        detalle.put("autoEliminar", exchange.isAutoDelete());
        detalle.put("interno", exchange.isInternal());
        detalle.put("argumentos", exchange.getArguments());
        return RespuestaOperacionDTO.de("Exchange creado", "exchange", nombre, detalle);
    }

    public RespuestaOperacionDTO borrarExchange(String nombre, boolean forzar) {
        proteccion.verificarBorrado(EXCHANGE, nombre, forzar);

        if (!management.existeExchange(nombre)) {
            throw new RecursoNoEncontradoException("El exchange '" + nombre + "' no existe");
        }
        if (!amqpAdmin.deleteExchange(nombre)) {
            if (nombre.startsWith(ProteccionRecursos.PREFIJO_RESERVADO)) {
                // forzar=true no alcanza: el broker nunca deja borrar sus amq.*.
                throw new RecursoProtegidoException("RabbitMQ rechazó borrar '" + nombre
                        + "': los exchanges amq.* pertenecen al broker y no se pueden borrar ni forzando");
            }
            throw new ConflictoException("RabbitMQ rechazó el borrado del exchange '" + nombre + "'");
        }
        log.info("Exchange borrado: nombre={} forzar={}", nombre, forzar);
        return RespuestaOperacionDTO.de("Exchange borrado", "exchange", nombre,
                Map.of("forzado", forzar && proteccion.esProtegido(nombre)));
    }

    // --------------------------------------------------------------- bindings

    /**
     * incluirPorDefecto=false oculta los bindings implícitos del exchange por
     * defecto (uno por cola, routing key = nombre de la cola): no se pueden
     * tocar y sólo meten ruido en la tabla de la consola.
     */
    public List<BindingDTO> listarBindings(boolean incluirPorDefecto) {
        return management.listarBindings().stream()
                .filter(b -> incluirPorDefecto || (b.source() != null && !b.source().isEmpty()))
                .map(this::aDto)
                .sorted(Comparator.comparing(BindingDTO::origen)
                        .thenComparing(BindingDTO::destino)
                        .thenComparing(BindingDTO::routingKey))
                .toList();
    }

    public RespuestaOperacionDTO crearBinding(BindingRequestDTO req) {
        // El broker respondería NOT_FOUND igual; verificarlo antes da un mensaje
        // que dice CUÁL de las dos puntas falta.
        if (!management.existeExchange(req.origen())) {
            throw new RecursoNoEncontradoException("El exchange origen '" + req.origen() + "' no existe");
        }
        boolean destinoExiste = req.destinoEsCola()
                ? amqpAdmin.getQueueInfo(req.destino()) != null
                : management.existeExchange(req.destino());
        if (!destinoExiste) {
            throw new RecursoNoEncontradoException((req.destinoEsCola() ? "La cola" : "El exchange")
                    + " destino '" + req.destino() + "' no existe");
        }
        // Redeclarar un binding idéntico es inocuo en AMQP (no duplica).
        amqpAdmin.declareBinding(aBinding(req));
        log.info("Binding creado: {} -> {} ({}) routingKey='{}'",
                req.origen(), req.destino(), req.tipoDestino(), req.routingKey());
        return RespuestaOperacionDTO.de("Binding creado", "binding", req.origen() + " -> " + req.destino(),
                detalleBinding(req));
    }

    public RespuestaOperacionDTO borrarBinding(BindingRequestDTO req, boolean forzar) {
        proteccion.verificarBorradoBinding(req.origen(), req.destino(), req.routingKey(), forzar);
        amqpAdmin.removeBinding(aBinding(req));
        log.info("Binding borrado: {} -> {} ({}) routingKey='{}' forzar={}",
                req.origen(), req.destino(), req.tipoDestino(), req.routingKey(), forzar);
        return RespuestaOperacionDTO.de("Binding borrado", "binding", req.origen() + " -> " + req.destino(),
                detalleBinding(req));
    }

    // ---------------------------------------------------------------- cluster

    public EstadoClusterDTO estadoCluster() {
        List<NodoDTO> nodos = management.listarNodos().stream()
                .map(ServicioRabbitAdmin::aDto)
                .sorted(Comparator.comparing(NodoDTO::nombre))
                .toList();
        String nombre = management.nombreCluster();
        int corriendo = (int) nodos.stream().filter(NodoDTO::corriendo).count();
        boolean particiones = nodos.stream().anyMatch(n -> !n.particiones().isEmpty());
        boolean alarmas = nodos.stream().anyMatch(n -> n.alarmaMemoria() || n.alarmaDisco());
        return new EstadoClusterDTO(nombre, nodos.size(), corriendo,
                // Mayoría estricta, igual que Raft: 2 de 3 sí, 1 de 3 no.
                corriendo * 2 > nodos.size(),
                particiones, alarmas, nodos, management.urlActiva(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- mapeos

    private ColaDTO aDto(ColaApi c) {
        return new ColaDTO(c.name(), c.vhost(), c.type(),
                Boolean.TRUE.equals(c.durable()), Boolean.TRUE.equals(c.autoDelete()),
                Boolean.TRUE.equals(c.exclusive()),
                c.messages(), c.messagesReady(), c.messagesUnacknowledged(), c.consumers(),
                c.state(), c.leader() != null ? c.leader() : c.node(),
                c.members() == null ? List.of() : c.members(),
                c.online() == null ? List.of() : c.online(),
                c.arguments() == null ? Map.of() : c.arguments(),
                proteccion.esProtegido(c.name()));
    }

    private ExchangeDTO aDto(ExchangeApi e) {
        return new ExchangeDTO(e.name(), e.vhost(), e.type(),
                Boolean.TRUE.equals(e.durable()), Boolean.TRUE.equals(e.autoDelete()),
                Boolean.TRUE.equals(e.internal()),
                e.arguments() == null ? Map.of() : e.arguments(),
                proteccion.esProtegido(e.name()));
    }

    private BindingDTO aDto(BindingApi b) {
        String origen = b.source() == null ? "" : b.source();
        return new BindingDTO(origen, b.destination(), b.destinationType(),
                b.routingKey() == null ? "" : b.routingKey(),
                b.arguments() == null ? Map.of() : b.arguments(),
                b.propertiesKey(),
                origen.isEmpty() || proteccion.esBindingProtegido(origen, b.destination()));
    }

    private static NodoDTO aDto(NodoApi n) {
        Double porcentaje = null;
        if (n.memUsed() != null && n.memLimit() != null && n.memLimit() > 0) {
            porcentaje = Math.round(n.memUsed() * 1000.0 / n.memLimit()) / 10.0;
        }
        return new NodoDTO(n.name(), n.type(), Boolean.TRUE.equals(n.running()),
                n.memUsed(), n.memLimit(), porcentaje, Boolean.TRUE.equals(n.memAlarm()),
                n.diskFree(), n.diskFreeLimit(), Boolean.TRUE.equals(n.diskFreeAlarm()),
                n.partitions() == null ? List.of() : n.partitions(),
                n.uptime());
    }

    private static Binding aBinding(BindingRequestDTO req) {
        Binding.DestinationType tipo = req.destinoEsCola()
                ? Binding.DestinationType.QUEUE
                : Binding.DestinationType.EXCHANGE;
        return new Binding(req.destino(), tipo, req.origen(), req.routingKey(), req.argumentos());
    }

    private static Map<String, Object> detalleBinding(BindingRequestDTO req) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("origen", req.origen());
        detalle.put("destino", req.destino());
        detalle.put("tipoDestino", req.tipoDestino());
        detalle.put("routingKey", req.routingKey());
        detalle.put("argumentos", req.argumentos());
        return detalle;
    }
}
