package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import com.robomart.notification.event.DlqConsumer;
import com.robomart.notification.service.DlqRecordStore;

class DlqConsumerTest {
    @Test
    void forwardsFullRawRecordAndNeverSwallowsDatabaseFailure() {
        DlqRecordStore store = mock(DlqRecordStore.class);
        var consumer = new DlqConsumer(store);
        var record = new ConsumerRecord<>("notification.dlq", 0, 20L, new byte[]{(byte) 255}, new byte[]{0, 1, 2});
        consumer.onDlqMessage(record);
        verify(store).capture(record);
        doThrow(new IllegalStateException("database unavailable")).when(store).capture(record);
        assertThatThrownBy(() -> consumer.onDlqMessage(record)).isInstanceOf(IllegalStateException.class);
    }
}
