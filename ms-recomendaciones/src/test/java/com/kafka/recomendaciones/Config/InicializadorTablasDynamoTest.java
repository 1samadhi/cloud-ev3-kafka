package com.kafka.recomendaciones.Config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Creacion de tablas con el cliente de bajo nivel mockeado. */
@SuppressWarnings("unchecked")
class InicializadorTablasDynamoTest {

    private final DynamoDbClient cliente = mock(DynamoDbClient.class);

    @Test
    void creaLasTresTablasSiNoExistenConStreamSoloEnOperaciones() {
        when(cliente.describeTable(any(Consumer.class)))
                .thenThrow(ResourceNotFoundException.builder().message("no existe").build());
        when(cliente.waiter()).thenReturn(mock(DynamoDbWaiter.class));

        new InicializadorTablasDynamo(cliente, "H", "R", "O").afterPropertiesSet();

        ArgumentCaptor<CreateTableRequest> captor = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(cliente, times(3)).createTable(captor.capture());
        List<CreateTableRequest> reqs = captor.getAllValues();
        assertThat(reqs).extracting(CreateTableRequest::tableName).containsExactly("H", "R", "O");
        assertThat(reqs).allMatch(r -> r.billingMode() == BillingMode.PAY_PER_REQUEST);
        assertThat(reqs.get(0).keySchema()).extracting(k -> k.attributeName()).containsExactly("usuarioId", "instanteEvento");
        assertThat(reqs.get(1).keySchema()).hasSize(1);
        assertThat(reqs.get(2).keySchema()).extracting(k -> k.attributeName()).containsExactly("tipo", "ocurridoEn");
        assertThat(reqs.get(0).streamSpecification()).isNull();
        assertThat(reqs.get(2).streamSpecification().streamViewType()).isEqualTo(StreamViewType.NEW_IMAGE);
        verify(cliente).updateTimeToLive(any(Consumer.class));
    }

    @Test
    void siYaExistenNoCreaNada() {
        when(cliente.describeTable(any(Consumer.class))).thenReturn(DescribeTableResponse.builder().build());

        new InicializadorTablasDynamo(cliente, "H", "R", "O").afterPropertiesSet();

        verify(cliente, never()).createTable(any(CreateTableRequest.class));
    }

    @Test
    void dynamoCaidoNoImpideArrancar() {
        when(cliente.describeTable(any(Consumer.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("sin conexion"));

        new InicializadorTablasDynamo(cliente, "H", "R", "O").afterPropertiesSet(); // no lanza
        verify(cliente, never()).createTable(any(CreateTableRequest.class));
    }
}
