# Evidencia 56 · Identidad con Cognito en el borde

Fecha: 2026-11-26 23:01 HSP. Cuenta 851725287694 · instancia i-0f5f47b9ffdefa327 · IP 18.211.30.140
Gateway `vih5lem02j` · user pool `us-east-1_Vaxf6Jhxx` · app client `3sf3mbsb67lo6njehp8voot8b4`

Todo contra AWS. Reproducible: `python infra/prueba-identidad.py <client-id>`

```
1. Login real contra el user pool
  [OK ] las dos cuentas inician sesion

2. Rutas protegidas: sin token y con token
  [OK ] GET /api/pedidos — sin token 401 · con token 200
  [OK ] GET /api/notificaciones — sin token 401 · con token 200
  [OK ] GET /api/admin/resumen — sin token 401 · con token 200
  [OK ] GET /api/productos/admin/stock — sin token 401 · con token 200

3. Rutas abiertas: el anonimo sigue comprando
  [OK ] GET /api/productos/categorias sin token — HTTP 200
  [OK ] GET /api/recomendaciones?userId=anon-verif sin token — HTTP 200
  [OK ] GET /api/carrito sin token — HTTP 200
  [OK ] POST /api/carrito/items sin token — HTTP 200

4. Preflight OPTIONS sin token (si falla, el navegador no llega)
  [OK ] OPTIONS /api/pedidos — HTTP 200
  [OK ] OPTIONS /api/pagos/iniciar — HTTP 200

5. Suplantacion por cabecera: el claim manda
  [OK ] 200 con el token de Ana — HTTP 200
  [OK ] ningun pedido de otro usuario aunque la cabecera diga admin — 0 pedidos ajenos

6. Compra completa por el gateway, con sesion real
  [OK ] agregar al carrito — HTTP 200
  [OK ] datos de envio (PUT, el verbo que faltaba) — HTTP 200
  [OK ] crear pedido — HTTP 200 {"id":"P-12","usuarioId":"ana@correo.cl","estado":"PENDIENTE_PAGO","metodoEnvio"
  [OK ] pago simulado — HTTP 200 {"estado":"AUTORIZADO","idOrden":"P-12","ultimos4":"1111"}
  [OK ] leer el pedido propio — HTTP 200

7. Antifraude por el gateway: el 429 llega al navegador
  [OK ] los 3 primeros pasan y el 4.o es 429 — [200, 200, 200, 429]

TODO OK
```

## El config.js que sirve la tienda (la prueba de que el gateway esta EN USO)

```js
/* ARCHIVO GENERADO EN EL ARRANQUE — no editar a mano, se sobrescribe.
   Generado: 2026-11-27 02:00:12 UTC */
window.PEDIDOS360_CONFIG = {
  productos:       "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo",
  carrito:         "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo",
  pagos:           "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo",
  recomendaciones: "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo",
  notificaciones:  "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo",
  cognitoRegion:   "us-east-1",
  cognitoClientId: "3sf3mbsb67lo6njehp8voot8b4",
  cognitoPoolId:   "us-east-1_Vaxf6Jhxx"
};
```

## Salud de los cinco servicios por el gateway

```
/salud/productos        HTTP 200  ACAO: http://18.211.30.140:8091
/salud/carrito          HTTP 200  ACAO: http://18.211.30.140:8091
/salud/pagos            HTTP 200  ACAO: http://18.211.30.140:8091
/salud/recomendaciones  HTTP 200  ACAO: http://18.211.30.140:8091
/salud/notificaciones   HTTP 200  ACAO: http://18.211.30.140:8091
```

## En el navegador (Chrome, IP de la sala de Diego en el security group)

| Qué | Resultado |
|---|---|
| Insignia de salud | **backend conectado** (antes del arreglo: "modo demo · sin backend") |
| Catálogo | 51 enlaces de producto pintados, todos por el gateway |
| Sesión de `ana@correo.cl` | "Hola, Ana" · 6 pedidos · 3 en curso |
| Sesión de `admin@pedidos360.cl` | `/admin` carga por el claim `cognito:groups`: ingresos 14 días $1.349.955 |
| Errores en consola | ninguno |
