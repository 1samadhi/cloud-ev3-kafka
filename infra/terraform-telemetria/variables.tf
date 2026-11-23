variable "region" {
  description = "Region de AWS. La del laboratorio academico suele ser us-east-1."
  type        = string
  default     = "us-east-1"
}

variable "entorno" {
  description = "Sufijo para nombrar los recursos y poder tener dos despliegues sin chocar."
  type        = string
  default     = "desarrollo"
}

variable "jar_lambda" {
  description = <<-DESC
    Ruta al jar sombreado de lambda-telemetria.

    Hay que construirlo ANTES del apply:
        cd ../../lambda-telemetria && mvn clean package
  DESC
  type        = string
  default     = "../../lambda-telemetria/target/lambda-telemetria-1.0.0.jar"
}

variable "memoria_mb" {
  description = <<-DESC
    Memoria de las Lambdas. En Lambda la CPU se asigna en proporcion a la
    memoria, asi que subirla acorta el arranque en frio de la JVM; 512 MB es el
    punto donde deja de notarse la mejora para este tamano de funcion.
  DESC
  type        = number
  default     = 512
}

variable "retencion_logs_dias" {
  description = "Dias de retencion en CloudWatch Logs. Sin esto, los grupos de log quedan para siempre."
  type        = number
  default     = 7
}
