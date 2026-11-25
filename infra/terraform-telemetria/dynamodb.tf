# =============================================================================
#  Tablas de DynamoDB
# =============================================================================

# Tabla de eventos del dashboard. Es la que dispara todo: cada escritura genera
# un registro en el stream y ese registro invoca la Lambda Procesador.
#
# Por que DynamoDB y no MySQL: MySQL no avisa de los cambios. Sin ese aviso el
# dashboard tendria que preguntar "hay algo nuevo?" cada pocos segundos, con
# retardo y con la respuesta vacia la mayoria de las veces.
resource "aws_dynamodb_table" "eventos" {
  name = "EventosOperaciones-${var.entorno}"

  # PAY_PER_REQUEST y no capacidad provisionada: la carga de una demostracion es
  # a rafagas, y con capacidad fija habria que elegir entre pagar de mas o que
  # la rafaga reciba throttling.
  billing_mode = "PAY_PER_REQUEST"

  hash_key  = "tipo"
  range_key = "ocurridoEn"

  attribute {
    name = "tipo"
    type = "S"
  }

  # Clave de ordenamiento temporal: permite leer "las ultimas alertas" sin
  # recorrer la tabla entera.
  attribute {
    name = "ocurridoEn"
    type = "S"
  }

  # NEW_IMAGE: al dashboard solo le interesa el dato nuevo. NEW_AND_OLD_IMAGES
  # duplicaria el tamano de cada registro del stream sin que nadie lea la
  # imagen vieja.
  stream_enabled   = true
  stream_view_type = "NEW_IMAGE"

  # Los eventos son telemetria: se miran en vivo y despues no sirven. El TTL los
  # borra solo y evita pagar almacenamiento por historial que nadie consulta.
  ttl {
    attribute_name = "expiraEn"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = false
  }
}

# Lista de navegadores conectados.
#
# Hace falta porque API Gateway NO la guarda: entrega un connectionId al
# conectar y despues hay que saber a quien escribirle.
resource "aws_dynamodb_table" "conexiones" {
  name         = "ConexionesWebSocket-${var.entorno}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "connectionId"

  attribute {
    name = "connectionId"
    type = "S"
  }

  # Red de seguridad: si una conexion se corta sin que $disconnect alcance a
  # limpiarla, el TTL la saca igual. El Procesador tambien borra las que
  # responden 410 Gone.
  ttl {
    attribute_name = "expiraEn"
    enabled        = true
  }
}
