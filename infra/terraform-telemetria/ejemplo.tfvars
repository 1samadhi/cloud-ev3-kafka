# Copiar como terraform.tfvars y ajustar.
#   terraform apply -var-file=terraform.tfvars

region  = "us-east-1"
entorno = "desarrollo"

# El jar tiene que estar construido antes del apply:
#   cd ../../lambda-telemetria && mvn clean package
jar_lambda = "../../lambda-telemetria/target/lambda-telemetria-1.0.0.jar"
