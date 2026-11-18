# =============================================================================
#  Las dos Lambdas
#
#  Un solo jar con dos handlers: comparten el modelo del evento y la mitad de
#  las dependencias, asi que separarlos en dos artefactos duplicaria 11 MB sin
#  ganar nada.
# =============================================================================

# El hash del jar entra en la configuracion de la funcion: sin esto, Terraform
# no detecta que el codigo cambio y un apply despues de recompilar no sube nada.
locals {
  hash_jar = filebase64sha256(var.jar_lambda)
}

# ---------------------------------------------------------------- Conexiones
resource "aws_lambda_function" "conexiones" {
  function_name = "pedidos360-conexiones-${var.entorno}"
  role          = aws_iam_role.conexiones.arn

  filename         = var.jar_lambda
  source_code_hash = local.hash_jar

  runtime = "java21"
  handler = "com.kafka.telemetria.ConexionesHandler::handleRequest"

  memory_size = var.memoria_mb

  # $connect tiene que resolverse rapido o el navegador ve la conexion fallar.
  # 10 s es holgado para un PutItem y acota el costo de un cuelgue.
  timeout = 10

  environment {
    variables = {
      TABLA_CONEXIONES = aws_dynamodb_table.conexiones.name
    }
  }

  depends_on = [aws_cloudwatch_log_group.conexiones]
}

# ---------------------------------------------------------------- Procesador
resource "aws_lambda_function" "procesador" {
  function_name = "pedidos360-procesador-${var.entorno}"
  role          = aws_iam_role.procesador.arn

  filename         = var.jar_lambda
  source_code_hash = local.hash_jar

  runtime = "java21"
  handler = "com.kafka.telemetria.ProcesadorHandler::handleRequest"

  memory_size = var.memoria_mb

  # Mas holgado que el portero: tiene que escribirle a todas las conexiones
  # abiertas, y cada postToConnection es una llamada de red.
  timeout = 30

  environment {
    variables = {
      TABLA_CONEXIONES = aws_dynamodb_table.conexiones.name

      # https:// y no wss://: el navegador se conecta con wss, la Lambda empuja
      # con https contra el MISMO endpoint. Son dos esquemas de la misma API y
      # confundirlos es el error clasico de este montaje.
      ENDPOINT_WEBSOCKET = "https://${aws_apigatewayv2_api.websocket.id}.execute-api.${var.region}.amazonaws.com/${aws_apigatewayv2_stage.produccion.name}"
    }
  }

  depends_on = [aws_cloudwatch_log_group.procesador]
}

# ---------------------------------------------- Disparador de DynamoDB Streams
resource "aws_lambda_event_source_mapping" "stream_eventos" {
  event_source_arn  = aws_dynamodb_table.eventos.stream_arn
  function_name     = aws_lambda_function.procesador.arn
  starting_position = "LATEST"

  # El lote es un techo, no una meta: lo que de verdad agrupa es la ventana de
  # 1 segundo, imperceptible en un tablero y suficiente para acotar la cantidad
  # de invocaciones.
  batch_size                         = 50
  maximum_batching_window_in_seconds = 1

  # LATEST y no TRIM_HORIZON: al desplegar no se reproduce todo el historial del
  # stream, que llenaria el tablero de eventos viejos.

  # Un evento que no se puede difundir no debe bloquear el shard: sin estos dos
  # limites, el mismo lote se reintenta hasta que expira y detiene la telemetria.
  maximum_retry_attempts        = 2
  maximum_record_age_in_seconds = 60
}

# ------------------------------------------------------------- Grupos de log
# Se declaran explicitamente para fijarles retencion. Si los crea Lambda sola,
# quedan con retencion "para siempre" y el costo crece sin que nadie lo mire.
resource "aws_cloudwatch_log_group" "conexiones" {
  name              = "/aws/lambda/pedidos360-conexiones-${var.entorno}"
  retention_in_days = var.retencion_logs_dias
}

resource "aws_cloudwatch_log_group" "procesador" {
  name              = "/aws/lambda/pedidos360-procesador-${var.entorno}"
  retention_in_days = var.retencion_logs_dias
}
