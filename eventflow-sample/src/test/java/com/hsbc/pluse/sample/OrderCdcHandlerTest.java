package com.hsbc.pluse.sample;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.test.CaptureOutboundPort;
import com.hsbc.pluse.test.EnvelopeFixtures;
import com.hsbc.pluse.test.EventProcessingTest;
import com.hsbc.pluse.test.MockInboundPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EventProcessingTest
class OrderCdcHandlerTest {

    @Autowired
    MockInboundPort inbound;

    @Autowired
    CaptureOutboundPort outbound;

    @BeforeEach
    void setUp() {
        outbound.reset();
    }

    @Test
    void should_process_order_insert_and_produce_two_outputs() {
        Envelope event = EnvelopeFixtures.cdcInsert("t_order", "12345", Map.of(
                "orderId", "12345",
                "amount", 99.9,
                "status", "CREATED"
        ));

        ProcessingResult result = inbound.send(event);

        assertThat(result).isInstanceOf(ProcessingResult.Success.class);
        var success = (ProcessingResult.Success) result;
        assertThat(success.outputs()).hasSize(2);
        assertThat(success.outputs().get(0).target()).isEqualTo("order-sync-out");
        assertThat(success.outputs().get(1).target()).isEqualTo("order-audit-log");
    }

    @Test
    void should_filter_delete_operations() {
        Envelope event = EnvelopeFixtures.cdcDelete("t_order", "12345");

        ProcessingResult result = inbound.send(event);

        assertThat(result).isInstanceOf(ProcessingResult.Filtered.class);
    }

    @Test
    void should_process_order_update() {
        Envelope event = EnvelopeFixtures.cdcUpdate("t_order", "12345", Map.of(
                "orderId", "12345",
                "amount", 199.9,
                "status", "PAID"
        ));

        ProcessingResult result = inbound.send(event);

        assertThat(result).isInstanceOf(ProcessingResult.Success.class);
    }
}
