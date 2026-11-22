package com.pedidos360.rabbitadmin.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** GET /api/cluster-name → {"name":"pedidos360"} (cluster_name de rabbitmq.conf). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NombreClusterApi(String name) {
}
