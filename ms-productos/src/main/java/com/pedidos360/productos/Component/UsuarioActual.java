package com.pedidos360.productos.Component;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.pedidos360.productos.Exception.PeticionInvalidaException;

/**
 * ÚNICO lugar donde se resuelve la identidad del usuario (§3.5).
 *
 * Hoy se lee el header {@code X-Usuario-Id}; en la fase 4 (Cognito) sólo cambia esta clase
 * para leer el {@code sub} del JWT. Por eso la firma no recibe la request: los llamadores
 * no saben de dónde sale el usuario.
 */
@Component
public class UsuarioActual {

    public static final String HEADER = "X-Usuario-Id";

    // Formato acotado: el valor termina como key de Kafka y en logs; se rechaza lo que
    // pueda inyectar saltos de línea en los logs o crecer sin límite.
    private static final Pattern FORMATO = Pattern.compile("^[A-Za-z0-9._@:+-]{1,64}$");

    /** Usuario de la petición en curso; vacío si no vino (navegación anónima). */
    public Optional<String> idUsuario() {
        RequestAttributes atributos = RequestContextHolder.getRequestAttributes();
        if (!(atributos instanceof ServletRequestAttributes servlet)) {
            return Optional.empty();
        }
        String valor = servlet.getRequest().getHeader(HEADER);
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        String limpio = valor.trim();
        if (!FORMATO.matcher(limpio).matches()) {
            throw new PeticionInvalidaException("Header " + HEADER
                    + " inválido: 1 a 64 caracteres entre letras, dígitos y . _ @ : + -");
        }
        return Optional.of(limpio);
    }
}
