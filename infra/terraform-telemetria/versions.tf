terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # El estado queda en local a proposito: es un proyecto de evaluacion con un
  # solo operador. Con mas de una persona aplicando cambios habria que mover el
  # backend a S3 con bloqueo en DynamoDB, o el ultimo apply pisa al anterior.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Proyecto = "Pedidos360"
      Ramo     = "DSY1107"
      Entorno  = var.entorno
      Gestion  = "terraform"
    }
  }
}
