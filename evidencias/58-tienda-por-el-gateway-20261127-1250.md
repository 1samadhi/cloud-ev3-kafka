# Evidencia 58 · La tienda entra entera por el API Gateway (lab de Ismael)

Fecha: 2026-11-27 12:50 (-03). Cuenta **471112736485** · instancia `i-0bdac4ec3cb93f53c` ·
IP **44.208.92.186** · gateway **`khca9i6w62`** · stage `desarrollo`.

Cierra la observación **F1 del docente en la EV2** ("API Gateway para el frontend") **en este
lab**. Hasta hoy estaba cerrada sólo en el de Diego (`evidencias/56`, cuenta 851725287694);
acá el `config.js` que servía la tienda apuntaba a `http://localhost:<puerto>`, así que la
página cargaba y **ningún dato llegaba** ("ms-productos no responde"): el navegador del
visitante pedía los datos a su propia máquina.

## Qué se hizo

| | |
|---|---|
| Rutas | se borró el subárbol `/api` que había dejado un script propio (CORS `'*'`, preflight MOCK) y se declararon las **35 rutas** de `despliegue/14-rutas-frontend-ev3.sh` (el de Diego, el que reenvía `Origin`), **0 fallos** |
| Tienda | las cinco `API_*` apuntan al stage, por `docker-compose.override.yml` (no se tocó `docker-compose.yml`) |
| Insignia de salud | parche `despliegue/parches/tienda-salud-por-el-gateway.patch` sobre `main` de la tienda: con una sola base se pide `/salud/<servicio>`, no `/actuator/health` |
| Consola | **a propósito sigue en los puertos directos**: usa rutas de administración que el `14` no publica (PUT de configuración de tópicos, particiones, reset de offsets, crear/borrar colas) |

El parche es el commit `958df92` de la rama `p4-cognito` de Diego, extraído **sin** la parte de
Cognito (en esa rama venían juntos, y el login de Cognito exige un user pool que este lab no
tiene). Compila: `tsc -b` + `vite build`, 57 módulos, 0 errores.

## Las 20 rutas medidas por el stage, con `Origin` de la tienda

```
GET    /api/productos                         200
GET    /api/productos?categoria=Consolas&q=switch&orden=precio 200
GET    /api/productos/categorias              200
GET    /api/recomendaciones?userId=humo@correo.cl 200
GET    /api/metricas                          200
GET    /api/carrito?usuarioId=humo@correo.cl  200
GET    /api/carrito/sucursales                200
GET    /api/pedidos?usuarioId=humo@correo.cl  200
GET    /api/admin/resumen                     200
GET    /api/productos/admin/stock             200
GET    /api/topicos                           200
GET    /api/cluster                           200
GET    /api/rabbit/colas                      200
GET    /api/alertas                           200
GET    /api/notificaciones?usuarioId=humo@correo.cl 200
GET    /salud/productos                       200
GET    /salud/carrito                         200
GET    /salud/pagos                           200
GET    /salud/recomendaciones                 200
GET    /salud/notificaciones                  200
```

Un 200 no prueba que el dato llegue, así que se midió el cuerpo:

```
productos      -> 20 productos (item_105 MacBook Air M2, $1.099.990…)
categorias     -> 8 categorías con su cantidad (celulares 3, laptops 3, audio 3…)
recomendaciones?userId=…  -> lista con motivo ("Popular entre otros…")
                  o sea LA QUERY STRING LLEGA: sin declararla el servicio
                  respondía "Required request parameter 'userId' is not present"
salud/productos -> {"status":"UP","groups":["liveness","readiness"],…}
```

## CORS: el origen real, no comodín (F3)

```
$ curl -D - -H "Origin: http://44.208.92.186:8091" .../api/productos
HTTP/2 200
access-control-allow-origin: http://44.208.92.186:8091
access-control-allow-credentials: true

$ curl -D - -H "Origin: http://evil.example.com" .../api/productos
HTTP/2 200
   (ninguna cabecera CORS: el navegador descarta la respuesta)

$ curl -X OPTIONS -H "Origin: http://44.208.92.186:8091" \
       -H "Access-Control-Request-Headers: x-usuario-id" .../api/productos/item_107
HTTP/2 200
access-control-allow-origin: http://44.208.92.186:8091
access-control-allow-headers: x-usuario-id
access-control-allow-methods: GET,POST,PUT,DELETE,OPTIONS
access-control-max-age: 3600
```

El valor no lo pone el gateway: lo responde Spring, que compara el origen contra
`CORS_ORIGENES`, y el gateway lo reenvía tal cual. Por eso el origen ajeno se queda sin
cabeceras en vez de recibir `*`.

## La prueba que ningún `curl` puede dar: el navegador

`curl` no aplica CORS y la prueba de humo va a los puertos directos, así que ninguna de las
dos habría detectado un fallo del navegador. Medido en la página real
(`http://44.208.92.186:8091`), leyendo el DOM y `performance.getEntriesByType('resource')`:

| Qué | Resultado |
|---|---|
| Insignia de la cabecera | **"backend conectado"** (los cinco servicios `UP`) |
| Catálogo | **20 productos en 8 categorías**, con stock real ("Quedan 9", "Quedan 4") |
| Llamadas de la página | **27 al gateway, 0 a `localhost`** |
| Rutas usadas | `/api/productos`, `/api/recomendaciones?userId=anon-…`, `/api/metricas` y las cinco `/salud/*` |
| Errores de consola | ninguno |

## Que no se rompió nada por los puertos directos

`BASE_HOST=44.208.92.186 python infra/prueba-humo.py` → **TODO OK** (25 comprobaciones),
incluido pedido `P-6` pagado por `pagos-resultado`, las tres notificaciones de RabbitMQ,
antifraude `[200, 200, 200, 429]` y el clúster de Rabbit con quórum.

## No verificado

- **Compra completa por el gateway desde el navegador** (agregar al carrito, envío, pago): se
  verificó por `curl`/prueba de humo y las rutas responden, pero no se tecleó en la página.
- **Cognito**: este lab **no** tiene user pool ni authorizer. Las 11 operaciones que en el lab
  de Diego piden token, acá están abiertas. La tienda usa el login de cuentas demo de `main`.
- La consola (`:8090`) sigue entrando por los puertos directos, a propósito (ver arriba).
- El repo de la instancia está en `95ec1af` y el de la tienda tiene el parche aplicado **sin
  commit**: el parche queda versionado en `despliegue/parches/` para poder repetirlo.
