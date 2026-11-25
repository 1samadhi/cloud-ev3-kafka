package com.kafka.telemetria;

/**
 * Nombres de las variables de entorno que inyecta Terraform.
 *
 * Estan centralizadas porque son el contrato entre la infraestructura y el
 * codigo: si una cambia de nombre en el template y no aca, la Lambda compila,
 * despliega y falla en la primera invocacion.
 */
final class Entorno {

    /** Tabla con un item por navegador conectado. */
    static final String TABLA_CONEXIONES = "TABLA_CONEXIONES";

    /**
     * Endpoint de administracion de la API WebSocket, en forma https://.
     *
     * El navegador se conecta con wss:// y la Lambda empuja con https:// contra
     * el MISMO endpoint: son dos esquemas de la misma API, y confundirlos es el
     * error clasico de este montaje.
     */
    static final String ENDPOINT_WEBSOCKET = "ENDPOINT_WEBSOCKET";

    private Entorno() {
    }

    static String requerido(String clave) {
        String valor = System.getenv(clave);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno " + clave);
        }
        return valor;
    }
}
