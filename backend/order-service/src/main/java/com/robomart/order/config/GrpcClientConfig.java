package com.robomart.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.payment.PaymentServiceGrpc;

@Configuration
public class GrpcClientConfig {

    @Bean
    public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(GrpcChannelFactory channelFactory) {
        return InventoryServiceGrpc.newBlockingStub(channelFactory.createChannel("inventory-service"));
    }

    @Bean
    public PaymentServiceGrpc.PaymentServiceBlockingStub paymentStub(GrpcChannelFactory channelFactory) {
        return PaymentServiceGrpc.newBlockingStub(channelFactory.createChannel("payment-service"));
    }
}
