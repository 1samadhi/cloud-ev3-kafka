package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.Exception.UsuarioNoIdentificadoException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * ÚNICO lugar donde se resuelve la identidad del usuario (§3.5). Hoy lee el header X-Usuario-Id;
 * en la fase 4 (Cognito, §12) se cambia esta clase para leer el "sub" del JWT y el resto del
 * servicio no se entera.
 */
@Component
public class UsuarioActual {

    public static final String HEADER = "X-Usuario-Id";

    /** Acotado: el valor viaja como key de Kafka y se guarda en columnas de 64 caracteres. */
    private static final Pattern FORMATO = Pattern.compile("^[A-Za-z0-9_.@:-]{1,64}$");

    private final HttpServletRequest request;

    /** Spring inyecta un proxy que resuelve la petición del hilo actual. */
    public UsuarioActual(HttpServletRequest request) {
        this.request = request;
    }

    public String obtener() {
        String valor = request.getHeader(HEADER);
        if (valor == null || valor.isBlank()) {
            throw new UsuarioNoIdentificadoException("Falta el header " + HEADER);
        }
        String usuario = valor.trim();
        if (!FORMATO.matcher(usuario).matches()) {
            throw new UsuarioNoIdentificadoException(HEADER + " inválido: 1 a 64 caracteres [A-Za-z0-9_.@:-]");
        }
        return usuario;
    }
}
