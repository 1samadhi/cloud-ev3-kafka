output "url_websocket" {
  description = <<-DESC
    URL que se pega en el campo "Endpoint WebSocket" de la pantalla de
    Operaciones, o que se pasa como WS_URL al contenedor del frontend.
  DESC
  value       = aws_apigatewayv2_stage.produccion.invoke_url
}

output "endpoint_administracion" {
  description = "El mismo endpoint en https://, que es el que usa la Lambda para empujar mensajes."
  value       = "https://${aws_apigatewayv2_api.websocket.id}.execute-api.${var.region}.amazonaws.com/${aws_apigatewayv2_stage.produccion.name}"
}

output "tabla_eventos" {
  description = "Tabla donde hay que escribir para que el tablero reciba datos."
  value       = aws_dynamodb_table.eventos.name
}

output "tabla_conexiones" {
  description = "Tabla con una fila por navegador conectado."
  value       = aws_dynamodb_table.conexiones.name
}

output "comando_prueba" {
  description = "Escribe un evento de prueba y deberia aparecer en el tablero al instante."
  value       = "aws dynamodb put-item --table-name ${aws_dynamodb_table.eventos.name} --item file://evento-prueba.json --region ${var.region}"
}
