package com.robomart.saga;

import com.robomart.proto.inventory.*;
import com.robomart.proto.payment.*;
import com.robomart.proto.saga.*;

/** Transport boundary; no Order entities or repositories are shared with the orchestrator. */
public interface SagaParticipants {
    WorkflowOrderResponse order(WorkflowOrderRequest q);

    OrderWorkflowResult command(OrderWorkflowCommand q);

    ReserveInventoryResponse reserve(ReserveInventoryRequest q);

    ReleaseInventoryResponse release(ReleaseInventoryRequest q);

    ChargeOutcome charge(ChargeOperation q, boolean close);

    ProcessPaymentResponse pay(ProcessPaymentRequest q);

    RefundOutcome refund(RefundOperation q, boolean execute);
}
