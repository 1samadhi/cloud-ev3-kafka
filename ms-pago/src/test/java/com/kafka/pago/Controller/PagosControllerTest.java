package com.kafka.pago.Controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.kafka.pago.DTO.AlertaFraudeDTO;
import com.kafka.pago.DTO.EstadoResultadoPago;
import com.kafka.pago.DTO.OrigenPago;
import com.kafka.pago.DTO.PeticionPagoDTO;
import com.kafka.pago.DTO.ResumenAlertasDTO;
import com.kafka.pago.DTO.TipoPasarela;
import com.kafka.pago.Exception.KafkaNoDisponibleException;
import com.kafka.pago.Exception.PasarelaNoDisponibleException;
import com.kafka.pago.Exception.TarjetaBloqueadaException;
import com.kafka.pago.Service.AlertasService;
import com.kafka.pago.Service.PasarelaWebpay.ConfirmacionWebpay;
import com.kafka.pago.Service.ServicioPagos;
import com.kafka.pago.Service.ServicioPagos.CobroInmediato;
import com.kafka.pago.Service.ServicioPagos.RedireccionWebpay;

/**
 * Capa HTTP de ms-pago sin Kafka ni base: validación (400), traducción de las
 * excepciones del servicio (429/503/502), respuestas de las dos pasarelas y el
 * redirect del commit de Webpay (lista cerrada, sin open redirect).
 */
@WebMvcTest(controllers = {PagosController.class, AlertasController.class})
class PagosControllerTest {

    private static final String NUMERO = "4051885600446623";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioPagos servicioPagos;

    @MockitoBean
    private AlertasService alertasService;

    // ------------------------------------------------------------- 400 validación

    @Test
    void cuerpoDeLaConsolaSinCamposObligatoriosDevuelve400ConDetalle() throws Exception {
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Datos inválidos"))
                .andExpect(jsonPath("$.detalle").value(containsString("clientId no puede estar vacío")))
                .andExpect(jsonPath("$.detalle").value(containsString("amount debe ser mayor a 0")))
                .andExpect(jsonPath("$.detalle").value(containsString("transactionId no puede estar vacío")))
                .andExpect(jsonPath("$.timestamp").exists());
        verify(servicioPagos, never()).iniciarPago(any());
    }

    @Test
    void simuladaSinTarjetaDevuelve400() throws Exception {
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\",\"pasarela\":\"SIMULADA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(
                        containsString("tarjeta.numero es obligatorio cuando la pasarela es SIMULADA")));
        verify(servicioPagos, never()).iniciarPago(any());
    }

    @Test
    void tarjetaMalFormadaDevuelve400SinRepetirElNumero() throws Exception {
        String numeroLargo = "40518856004466231234567"; // 23 dígitos: fuera de 13–19
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\","
                                + "\"pasarela\":\"SIMULADA\",\"tarjeta\":{\"numero\":\"" + numeroLargo + "\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("entre 13 y 19 dígitos")))
                .andExpect(content().string(not(containsString(numeroLargo))));
    }

    @Test
    void pasarelaOOrigenDesconocidosOJsonRotoDevuelven400() throws Exception {
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\",\"pasarela\":\"PAYPAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Datos inválidos"));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\",\"origen\":\"https://evil.example\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON).content("{roto"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void erroresEstandarDeSpringConservanSuCodigoYNoSon500() throws Exception {
        mvc.perform(get("/api/pagos/iniciar")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ------------------------------------------------------------- 429 / 503 / 502

    @Test
    void huellaBloqueadaDevuelve429ConLaFormaQueLeeLaConsola() throws Exception {
        when(servicioPagos.iniciarPago(any())).thenThrow(
                new TarjetaBloqueadaException(LocalDateTime.of(2026, 11, 22, 12, 30, 0), 4, 30));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(startsWith("Demasiados intentos detectados")))
                .andExpect(jsonPath("$.bloqueadoHasta").value("2026-11-22T12:30:00"))
                .andExpect(jsonPath("$.intentos").value(4))
                .andExpect(jsonPath("$.minutosRestantes").value(30));
    }

    @Test
    void kafkaSinConfirmarDevuelve503() throws Exception {
        when(servicioPagos.iniciarPago(any())).thenThrow(new KafkaNoDisponibleException("sin ack", null));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Servicio antifraude no disponible"));
    }

    @Test
    void transbankCaidoDevuelve502() throws Exception {
        when(servicioPagos.iniciarPago(any())).thenThrow(new PasarelaNoDisponibleException("caído", null));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\"}"))
                .andExpect(status().isBadGateway());
    }

    // ------------------------------------------------------------- 200 / 402

    @Test
    void webpayPorDefectoRespondeUrlYTokenComoAntes() throws Exception {
        when(servicioPagos.iniciarPago(any())).thenReturn(new RedireccionWebpay("https://webpay3gint.transbank.cl/x", "tok123"));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://webpay3gint.transbank.cl/x"))
                .andExpect(jsonPath("$.token").value("tok123"));

        ArgumentCaptor<PeticionPagoDTO> captor = ArgumentCaptor.forClass(PeticionPagoDTO.class);
        verify(servicioPagos).iniciarPago(captor.capture());
        assertThat(captor.getValue().getPasarela()).isEqualTo(TipoPasarela.WEBPAY);
        assertThat(captor.getValue().getOrigen()).isEqualTo(OrigenPago.CONSOLA);
    }

    @Test
    void simuladaAprobadaDevuelve200() throws Exception {
        when(servicioPagos.iniciarPago(any())).thenReturn(new CobroInmediato(EstadoResultadoPago.AUTORIZADO, "TX-1", "6623"));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-1\",\"pasarela\":\"SIMULADA\","
                                + "\"origen\":\"TIENDA\",\"tarjeta\":{\"numero\":\"4051 8856 0044 6623\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("AUTORIZADO"))
                .andExpect(jsonPath("$.idOrden").value("TX-1"))
                .andExpect(jsonPath("$.ultimos4").value("6623"))
                .andExpect(content().string(not(containsString(NUMERO))));
    }

    @Test
    void simuladaRechazadaDevuelve402() throws Exception {
        when(servicioPagos.iniciarPago(any())).thenReturn(new CobroInmediato(EstadoResultadoPago.RECHAZADO, "TX-2", "0002"));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"ana\",\"amount\":1000,\"transactionId\":\"TX-2\",\"pasarela\":\"SIMULADA\","
                                + "\"tarjeta\":{\"numero\":\"4000000000000002\"}}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.ultimos4").value("0002"));
    }

    // ------------------------------------------------------------- commit de Webpay

    private static ConfirmacionWebpay autorizada() {
        return new ConfirmacionWebpay("AUTHORIZED", "TX-9", "ana", 1500L, "1213", "2026-11-22T12:00:00Z", "6623");
    }

    @Test
    void commitConOrigenTiendaRedirigeALaTienda() throws Exception {
        when(servicioPagos.confirmarWebpay("tok")).thenReturn(autorizada());
        mvc.perform(post("/api/pagos/commit").param("token_ws", "tok").param("origen", "TIENDA"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:5174/callback?estado=AUTHORIZED")))
                .andExpect(header().string("Location", containsString("orden=TX-9")))
                .andExpect(header().string("Location", containsString("tarjeta=6623")));
    }

    @Test
    void commitPorGetConOrigenConsolaRedirigeALaConsola() throws Exception {
        when(servicioPagos.confirmarWebpay("tok")).thenReturn(
                new ConfirmacionWebpay("FAILED", "TX-9", "ana", 1500L, null, "f", null));
        mvc.perform(get("/api/pagos/commit").param("token_ws", "tok").param("origen", "CONSOLA"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:5173/callback?estado=REJECTED")));
    }

    @Test
    void origenManipuladoNuncaProduceUnOpenRedirect() throws Exception {
        when(servicioPagos.confirmarWebpay("tok")).thenReturn(autorizada());
        for (String malicioso : List.of("https://evil.example", "//evil.example", "TIENDA@evil.example", "")) {
            MvcResult r = mvc.perform(post("/api/pagos/commit").param("token_ws", "tok").param("origen", malicioso))
                    .andExpect(status().isFound())
                    .andReturn();
            URI destino = URI.create(r.getResponse().getHeader("Location"));
            assertThat(destino.getHost()).as("origen=%s", malicioso).isEqualTo("localhost");
            assertThat(destino.getPort()).isEqualTo(5173);
            assertThat(destino.getPath()).isEqualTo("/callback");
        }
        // Sin parámetro origen (returnUrl viejo) también vuelve a la consola.
        mvc.perform(post("/api/pagos/commit").param("token_ws", "tok"))
                .andExpect(header().string("Location", startsWith("http://localhost:5173/callback")));
    }

    @Test
    void anulacionSinTokenWsRedirigeAnuladoSegunOrigen() throws Exception {
        mvc.perform(post("/api/pagos/commit").param("TBK_TOKEN", "t").param("TBK_ORDEN_COMPRA", "TX-5")
                        .param("TBK_ID_SESION", "ana").param("origen", "TIENDA"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:5174/callback?estado=ANULADO")))
                .andExpect(header().string("Location", containsString("orden=TX-5")));
        verify(servicioPagos).registrarAnulacionWebpay("TX-5", "ana");
        verify(servicioPagos, never()).confirmarWebpay(anyString());
    }

    @Test
    void errorAlConfirmarRedirigeConEstadoError() throws Exception {
        when(servicioPagos.confirmarWebpay("tok")).thenThrow(new PasarelaNoDisponibleException("Transbank no responde", null));
        mvc.perform(post("/api/pagos/commit").param("token_ws", "tok").param("origen", "TIENDA"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:5174/callback?estado=ERROR")));
    }

    // ------------------------------------------------------------- /api/alertas

    @Test
    void alertasMantieneLaFormaDeLaConsolaYAgregaHuellaYUltimos4() throws Exception {
        LocalDateTime hasta = LocalDateTime.of(2026, 11, 22, 12, 30, 0);
        when(alertasService.obtenerResumenAlertas()).thenReturn(new ResumenAlertasDTO(1, 7, List.of(
                new AlertaFraudeDTO("ana", 4, hasta, 29, hasta.minusMinutes(30), "abcdef012345", "6623"))));
        mvc.perform(get("/api/alertas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloqueosActivos").value(1))
                .andExpect(jsonPath("$.totalClientesVigilados").value(7))
                .andExpect(jsonPath("$.alertas[0].idCliente").value("ana"))
                .andExpect(jsonPath("$.alertas[0].cantidadIntentos").value(4))
                .andExpect(jsonPath("$.alertas[0].bloqueadoHasta").value("2026-11-22T12:30:00"))
                .andExpect(jsonPath("$.alertas[0].minutosRestantes").value(29))
                .andExpect(jsonPath("$.alertas[0].actualizadoEn").value("2026-11-22T12:00:00"))
                .andExpect(jsonPath("$.alertas[0].huellaTarjeta").value("abcdef012345"))
                .andExpect(jsonPath("$.alertas[0].ultimos4").value("6623"));
    }

    @Test
    void corsPermiteLaTiendaEnIniciar() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/pagos/iniciar")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));
    }
}
