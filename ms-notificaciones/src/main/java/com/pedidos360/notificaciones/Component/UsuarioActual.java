package com.pedidos360.notificaciones.Component;

import com.pedidos360.notificaciones.Exception.UsuarioNoIdentificadoException;
import org.springframework.stereotype.Component;

/**
 * ÚNICO lugar donde se decide quién es el usuario (§3.5). Hoy: ?usuarioId= (lo
 * que puede mandar EventSource, que no admite headers) o el header X-Usuario-Id.
 * En la fase 4 (Cognito) sólo cambia esta clase para leer el "sub" del JWT.
 */
@Component
public class UsuarioActual {

    public String resolver(String desdeQuery, String desdeHeader) {
        if (desdeQuery != null && !desdeQuery.isBlank()) {
            return desdeQuery.trim();
        }
        if (desdeHeader != null && !desdeHeader.isBlank()) {
            return desdeHeader.trim();
        }
        throw new UsuarioNoIdentificadoException();
    }
}
