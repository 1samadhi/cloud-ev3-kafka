# =============================================================================
#  Permisos
#
#  Dos roles y no uno compartido: cada Lambda recibe solo lo que necesita, y
#  tener roles separados hace evidente en la consola quien puede hacer que.
# =============================================================================

data "aws_iam_policy_document" "asumir_lambda" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------- Conexiones
resource "aws_iam_role" "conexiones" {
  name               = "pedidos360-conexiones-${var.entorno}"
  assume_role_policy = data.aws_iam_policy_document.asumir_lambda.json
}

data "aws_iam_policy_document" "conexiones" {
  # Solo escribe y borra en la tabla de conexiones. No lee eventos ni empuja
  # mensajes: es el portero, no el que difunde.
  statement {
    actions   = ["dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.conexiones.arn]
  }
}

resource "aws_iam_role_policy" "conexiones" {
  name   = "acceso-conexiones"
  role   = aws_iam_role.conexiones.id
  policy = data.aws_iam_policy_document.conexiones.json
}

resource "aws_iam_role_policy_attachment" "conexiones_logs" {
  role       = aws_iam_role.conexiones.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# ---------------------------------------------------------------- Procesador
resource "aws_iam_role" "procesador" {
  name               = "pedidos360-procesador-${var.entorno}"
  assume_role_policy = data.aws_iam_policy_document.asumir_lambda.json
}

data "aws_iam_policy_document" "procesador" {
  # Leer el stream de eventos.
  statement {
    actions = [
      "dynamodb:DescribeStream",
      "dynamodb:GetRecords",
      "dynamodb:GetShardIterator",
      "dynamodb:ListStreams",
    ]
    resources = [aws_dynamodb_table.eventos.stream_arn]
  }

  # Recorrer la tabla de conexiones y borrar las caducas.
  statement {
    actions   = ["dynamodb:Scan", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.conexiones.arn]
  }

  # Empujar mensajes a las conexiones abiertas. El permiso se acota a la etapa
  # concreta de esta API: con "*" cualquier Lambda del proyecto podria escribir
  # a cualquier WebSocket de la cuenta.
  statement {
    actions   = ["execute-api:ManageConnections"]
    resources = ["${aws_apigatewayv2_api.websocket.execution_arn}/${aws_apigatewayv2_stage.produccion.name}/POST/@connections/*"]
  }
}

resource "aws_iam_role_policy" "procesador" {
  name   = "acceso-procesador"
  role   = aws_iam_role.procesador.id
  policy = data.aws_iam_policy_document.procesador.json
}

resource "aws_iam_role_policy_attachment" "procesador_logs" {
  role       = aws_iam_role.procesador.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}
