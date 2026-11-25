package com.kafka.recomendaciones.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cuerpo HISTORICO de POST /api/interacciones, el que envia la consola React.
 *
 * Se conserva tal cual para no romperla; el servicio lo traduce a
 * EventoInteraccionDTO antes de publicar (§5.2). Nunca viaja por Kafka.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InteractionRequestsDTO {

    @NotBlank(message = "clientId es obligatorio")
    private String clientId;

    @NotBlank(message = "itemId es obligatorio")
    private String itemId;

    /** view | cart | purchase */
    @NotBlank(message = "action es obligatorio (view, cart o purchase)")
    private String action;

    private String category;
    private String productType;
}
