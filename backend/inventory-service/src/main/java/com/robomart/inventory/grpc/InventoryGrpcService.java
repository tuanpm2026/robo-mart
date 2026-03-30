package com.robomart.inventory.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.robomart.proto.inventory.GetInventoryRequest;
import com.robomart.proto.inventory.GetInventoryResponse;
import com.robomart.proto.inventory.InventoryServiceGrpc;
import com.robomart.proto.inventory.ReleaseInventoryRequest;
import com.robomart.proto.inventory.ReleaseInventoryResponse;
import com.robomart.proto.inventory.ReserveInventoryRequest;
import com.robomart.proto.inventory.ReserveInventoryResponse;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    @Override
    public void reserveInventory(ReserveInventoryRequest request, StreamObserver<ReserveInventoryResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("ReserveInventory not yet implemented").asRuntimeException());
    }

    @Override
    public void releaseInventory(ReleaseInventoryRequest request, StreamObserver<ReleaseInventoryResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("ReleaseInventory not yet implemented").asRuntimeException());
    }

    @Override
    public void getInventory(GetInventoryRequest request, StreamObserver<GetInventoryResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription("GetInventory not yet implemented").asRuntimeException());
    }
}
