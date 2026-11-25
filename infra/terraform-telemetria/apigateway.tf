# =============================================================================
#  API Gateway WebSocket — el canal
#
#  Por que WebSocket y no HTTP: HTTP es pregunta-respuesta, el cliente tiene que
#  preguntar. Para un tablero que debe reaccionar al instante, preguntar cada
#  segundo es desperdicio y aun asi llega tarde.
#
#  Por que API Gateway y no un WebSocket propio: no hay servidor que mantener,
#  escala solo y se paga por mensaje. El detalle a tener en cuenta es que cierra
#  la conexion a los 10 minutos de inactividad, asi que el frontend reconecta.
# =============================================================================

resource "aws_apigatewayv2_api" "websocket" {
  name          = "pedidos360-operaciones-${var.entorno}"
  protocol_type = "WEBSOCKET"

  # De donde sale el nombre de la ruta en cada mensaje que manda el cliente.
  # "$request.body.action" es la convencion de API Gateway para WebSocket.
  route_selection_expression = "$request.body.action"
}

# ---------------------------------------------------------------- Integraciones
# AWS_PROXY y no AWS: el evento llega tal cual a la Lambda, sin plantillas de
# mapeo que haya que mantener en la consola.
resource "aws_apigatewayv2_integration" "conectar" {
  api_id           = aws_apigatewayv2_api.websocket.id
  integration_type = "AWS_PROXY"
  integration_uri  = aws_lambda_function.conexiones.invoke_arn
}

# ----------------------------------------------------------------------- Rutas
resource "aws_apigatewayv2_route" "connect" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$connect"
  target    = "integrations/${aws_apigatewayv2_integration.conectar.id}"
}

resource "aws_apigatewayv2_route" "disconnect" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$disconnect"
  target    = "integrations/${aws_apigatewayv2_integration.conectar.id}"
}

# $default existe para que un mensaje del cliente no devuelva un error opaco.
# El tablero solo escucha, pero sin esta ruta cualquier mensaje enviado por
# equivocacion cierra la conexion.
resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.conectar.id}"
}

# ----------------------------------------------------------------------- Etapa
resource "aws_apigatewayv2_stage" "produccion" {
  api_id      = aws_apigatewayv2_api.websocket.id
  name        = var.entorno
  auto_deploy = true

  default_route_settings {
    # Los logs de ejecucion de API Gateway son lo unico que explica por que una
    # conexion fue rechazada: sin esto, el navegador solo ve "connection failed".
    logging_level            = "INFO"
    data_trace_enabled       = false
    detailed_metrics_enabled = true
    throttling_burst_limit   = 100
    throttling_rate_limit    = 50
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.apigateway.arn
    format = jsonencode({
      requestId    = "$context.requestId"
      ip           = "$context.identity.sourceIp"
      routeKey     = "$context.routeKey"
      connectionId = "$context.connectionId"
      status       = "$context.status"
      error        = "$context.error.message"
    })
  }
}

resource "aws_cloudwatch_log_group" "apigateway" {
  name              = "/aws/apigateway/pedidos360-operaciones-${var.entorno}"
  retention_in_days = var.retencion_logs_dias
}

# -------------------------------------------------------------------- Permiso
# Sin esto la ruta existe pero API Gateway no puede invocar la funcion, y el
# navegador recibe un 500 al conectar.
resource "aws_lambda_permission" "apigateway_invoca" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.conexiones.function_name
  principal     = "apigateway.amazonaws.com"

  # El origen se acota a esta API: con "*" cualquier API de la cuenta podria
  # invocar al portero.
  source_arn = "${aws_apigatewayv2_api.websocket.execution_arn}/*/*"
}
