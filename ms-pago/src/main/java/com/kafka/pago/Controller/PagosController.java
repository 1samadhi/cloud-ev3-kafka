package com.kafka.pago.Controller;

import com.kafka.pago.Service.PagosServices;
import com.kafka.pago.DTO.PeticionPagoDTO;
import com.kafka.pago.DTO.TransaccionRespuestaDTO;

import cl.transbank.common.IntegrationApiKeys;
import cl.transbank.webpay.webpayplus.WebpayPlus;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/pagos")
public class PagosController {

    @Autowired 
    private PagosServices pagosServices;

    /**
     * URL a la que Transbank devuelve al navegador cuando el cliente termina en
     * Webpay. Apunta a ESTE servicio, no al frontend: Transbank hace un POST con
     * token_ws en el cuerpo de un formulario, y una SPA servida como archivos
     * estáticos no puede leer el cuerpo de un POST. El commit se hace acá y
     * recién después se redirige al frontend por GET con el resultado.
     *
     * Antes estaba escrita a mano como "http://localhost:5173/callback", que no
     * existía: el token se perdía y ningún pago llegaba a confirmarse.
     */
    @Value("${app.pagos.return-url:http://localhost:8083/api/pagos/commit}")
    private String returnUrl;

    /**
     * Base del frontend al que se redirige con el resultado ya confirmado.
     * Se parametriza porque en AWS el front no vive en localhost.
     */
    @Value("${app.front.url:http://localhost:5173}")
    private String frontUrl;

    @PostMapping("/iniciar")
    public ResponseEntity<?> iniciarPago(@Valid @RequestBody PeticionPagoDTO peticion) {
        // 1. El servicio ahora evalúa si ya pasaron los 30 minutos de forma automática
        if (pagosServices.verificarSiUsuarioEstaBloqueado(peticion.getClientId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Demasiados intentos detectados. Estás bloqueado temporalmente por 30 minutos. Por favor, espera."));
        }

        // 2. Si ya pasó el tiempo (o nunca se bloqueó), continúa con Kafka y Webpay normalmente...
        pagosServices.producirIntentoPago(peticion.getClientId(), peticion.getAmount(), peticion.getTransactionId());

        try {
            TransaccionRespuestaDTO transaccionWebpay = pagosServices.iniciarTransaccionWebpay(
                    peticion.getTransactionId(),
                    peticion.getClientId(),
                    peticion.getAmount().doubleValue(),
                    returnUrl
            );

            return ResponseEntity.ok(Map.of(
                    "url", transaccionWebpay.getUrlWebpay(),
                    "token", transaccionWebpay.getToken()
            ));

        } catch (Exception e) {
            log.error("Error al iniciar la transacción con Webpay Plus: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "No se pudo conectar con la pasarela de pagos"));
        }
    }

    /**
     * Vuelta de Webpay. Transbank entra acá con POST y, según el flujo de
     * anulación, puede volver con GET: se aceptan ambos verbos.
     *
     * Los parámetros son opcionales a propósito. Cuando el cliente abandona el
     * formulario, Transbank NO envía token_ws sino TBK_TOKEN junto con la orden
     * de compra; exigir token_ws haría fallar con un 400 una anulación normal.
     *
     * Responde SIEMPRE un redirect 302 al frontend, así el navegador queda en la
     * consola y no mirando un JSON crudo.
     */
    @RequestMapping(value = "/commit", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Void> confirmarPagoWebpay(
            @RequestParam(value = "token_ws", required = false) String tokenWs,
            @RequestParam(value = "TBK_TOKEN", required = false) String tbkToken,
            @RequestParam(value = "TBK_ORDEN_COMPRA", required = false) String tbkOrdenCompra,
            @RequestParam(value = "TBK_ID_SESION", required = false) String tbkIdSesion) {

        // --- Caso 1: el cliente anuló el pago en el formulario de Webpay ---
        if (tokenWs == null || tokenWs.isBlank()) {
            log.warn("Vuelta de Webpay sin token_ws (anulación). TBK_TOKEN={}, orden={}", tbkToken, tbkOrdenCompra);

            // Una anulación cuenta como intento no exitoso: el contador del
            // antifraude NO se limpia (ver gestionarResultadoCallbackPago).
            if (tbkIdSesion != null && !tbkIdSesion.isBlank()) {
                pagosServices.gestionarResultadoCallbackPago(tbkIdSesion, false);
            }

            return redirigir(UriComponentsBuilder.fromUriString(frontUrl + "/callback")
                    .queryParam("estado", "ANULADO")
                    .queryParam("orden", textoSeguro(tbkOrdenCompra))
                    .queryParam("cliente", textoSeguro(tbkIdSesion)));
        }

        // --- Caso 2: hay token, se confirma contra Transbank ---
        try {
            // Instanciar usando el builder oficial para integración en la versión 6.x
            WebpayPlus.Transaction tx = WebpayPlus.Transaction.buildForIntegration(
                "597055555532", 
                IntegrationApiKeys.WEBPAY
            );

            // Ejecutamos el commit con el token que devuelve Transbank
            var response = tx.commit(tokenWs);
            boolean autorizado = "AUTHORIZED".equals(response.getStatus());

            pagosServices.gestionarResultadoCallbackPago(response.getSessionId(), autorizado);

            if (autorizado) {
                log.info("¡Pago exitoso! Orden de compra: {}, Monto: {}", response.getBuyOrder(), response.getAmount());
            } else {
                log.warn("Pago rechazado por Transbank para la orden: {}", response.getBuyOrder());
            }

            UriComponentsBuilder destino = UriComponentsBuilder.fromUriString(frontUrl + "/callback")
                    .queryParam("estado", autorizado ? "AUTHORIZED" : "REJECTED")
                    .queryParam("orden", textoSeguro(response.getBuyOrder()))
                    .queryParam("cliente", textoSeguro(response.getSessionId()))
                    .queryParam("monto", textoSeguro(response.getAmount()))
                    .queryParam("autorizacion", textoSeguro(response.getAuthorizationCode()))
                    .queryParam("fecha", textoSeguro(response.getTransactionDate()));

            // El detalle de tarjeta puede venir nulo en transacciones rechazadas.
            if (response.getCardDetail() != null) {
                destino.queryParam("tarjeta", textoSeguro(response.getCardDetail().getCardNumber()));
            }

            return redirigir(destino);

        } catch (Exception e) {
            log.error("Error al hacer commit en Transbank: {}", e.getMessage());
            return redirigir(UriComponentsBuilder.fromUriString(frontUrl + "/callback")
                    .queryParam("estado", "ERROR")
                    .queryParam("detalle", textoSeguro(e.getMessage())));
        }
    }

    private static String textoSeguro(Object valor) {
        return valor == null ? "" : valor.toString();
    }

    /** 302 + Location: el navegador sigue el redirect y queda en la consola. */
    private static ResponseEntity<Void> redirigir(UriComponentsBuilder builder) {
        URI destino = builder.build().encode().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(destino).build();
    }
}
