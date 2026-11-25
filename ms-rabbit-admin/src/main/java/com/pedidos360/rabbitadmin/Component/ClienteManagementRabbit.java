package com.pedidos360.rabbitadmin.Component;

import com.pedidos360.rabbitadmin.Config.RabbitManagementProperties;
import com.pedidos360.rabbitadmin.Exception.ManagementNoDisponibleException;
import com.pedidos360.rabbitadmin.Model.BindingApi;
import com.pedidos360.rabbitadmin.Model.ColaApi;
import com.pedidos360.rabbitadmin.Model.ExchangeApi;
import com.pedidos360.rabbitadmin.Model.NodoApi;
import com.pedidos360.rabbitadmin.Model.NombreClusterApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente de la API HTTP de management (sólo lectura).
 *
 * Failover entre nodos: si un nodo no responde (conexión rechazada, timeout) o
 * responde 5xx (p. ej. pausado por pause_minority), se prueba el siguiente. El
 * último que respondió queda como preferido, para no pagar el timeout de un
 * nodo caído en cada consulta. Un 4xx NO hace failover: 401 es un problema de
 * credenciales y 404 es una respuesta válida ("no existe"); otro nodo diría lo mismo.
 */
@Slf4j
@Component
public class ClienteManagementRabbit {

    private final RestClient restClient;
    private final List<String> urls;
    private final String vhost;
    private final AtomicInteger indicePreferido = new AtomicInteger(0);

    public ClienteManagementRabbit(@Qualifier("restClientManagement") RestClient restClient,
                                   RabbitManagementProperties props) {
        this.restClient = restClient;
        this.urls = props.getUrls().stream()
                .map(String::trim)
                .filter(u -> !u.isEmpty())
                // Sin barra final: si no, la ruta quedaría "//api/queues".
                .map(u -> u.replaceAll("/+$", ""))
                .toList();
        if (this.urls.isEmpty()) {
            throw new IllegalStateException("app.rabbit.management.urls (RABBITMQ_MGMT_URL) no tiene ninguna URL");
        }
        this.vhost = props.getVhost();
    }

    public List<ColaApi> listarColas() {
        return lista(get(ColaApi[].class, "api", "queues", vhost));
    }

    public List<ExchangeApi> listarExchanges() {
        return lista(get(ExchangeApi[].class, "api", "exchanges", vhost));
    }

    public List<BindingApi> listarBindings() {
        return lista(get(BindingApi[].class, "api", "bindings", vhost));
    }

    public List<NodoApi> listarNodos() {
        return lista(get(NodoApi[].class, "api", "nodes"));
    }

    public String nombreCluster() {
        NombreClusterApi r = get(NombreClusterApi.class, "api", "cluster-name");
        return r == null ? null : r.name();
    }

    /** AmqpAdmin no tiene "existe exchange": se pregunta a la API (404 = no existe). */
    public boolean existeExchange(String nombre) {
        try {
            get(ExchangeApi.class, "api", "exchanges", vhost, nombre);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /** URL del último nodo que respondió (la que se usará primero en la próxima consulta). */
    public String urlActiva() {
        return urls.get(indicePreferido.get());
    }

    private <T> T get(Class<T> tipo, String... segmentos) {
        int total = urls.size();
        int inicio = indicePreferido.get();
        List<String> fallos = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int indice = (inicio + i) % total;
            String base = urls.get(indice);
            URI uri = construirUri(base, segmentos);
            try {
                T cuerpo = restClient.get().uri(uri).retrieve().body(tipo);
                if (indice != inicio) {
                    log.warn("API de management: failover a {} (no respondió {})", base, urls.get(inicio));
                }
                indicePreferido.set(indice);
                return cuerpo;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                log.warn("API de management en {} no disponible: {}", base, e.getMessage());
                fallos.add(base + " -> " + e.getMessage());
            }
        }
        throw new ManagementNoDisponibleException(
                "Ningún nodo respondió en la API de management: " + String.join(" | ", fallos));
    }

    /**
     * pathSegment + encode codifica cada segmento por separado: el vhost "/" se
     * vuelve %2F (como exige la API) y un nombre con caracteres especiales no
     * puede inyectar segmentos de ruta.
     */
    static URI construirUri(String base, String... segmentos) {
        return UriComponentsBuilder.fromUriString(base)
                .pathSegment(segmentos)
                .build()
                .encode()
                .toUri();
    }

    private static <T> List<T> lista(T[] arreglo) {
        return arreglo == null ? List.of() : Arrays.asList(arreglo);
    }
}
