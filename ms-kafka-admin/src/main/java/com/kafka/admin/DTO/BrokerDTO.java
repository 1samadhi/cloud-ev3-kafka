package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para representar un broker en el cluster.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrokerDTO {
    private int id;
    private String host;
}
