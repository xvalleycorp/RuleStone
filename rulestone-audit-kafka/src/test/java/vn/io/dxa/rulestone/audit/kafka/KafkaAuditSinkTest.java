//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.audit.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.BufferExhaustedException;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.AuditException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionRecordJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

class KafkaAuditSinkTest {

    private static final String TABLE = """
            {
              "id": "gate_decision",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs": { "score": "int" },
              "outputs": { "action": "string" },
              "rules": [
                { "id": "BLOCK", "when": "score >= 70", "then": { "action": "'BLOCK'" }, "reasonCode": "SCORE_HIGH" },
                { "id": "ALLOW", "when": "true", "then": { "action": "'ALLOW'" } }
              ]
            }
            """;

    private static final DecisionRef REF = DecisionRef.of("gate_decision").asOf(LocalDate.of(2026, 11, 1));
    private static final Facts FACTS = Facts.of("score", 80);

    private final MockProducer<String, String> producer =
            new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());

    private static RuleStoneEngine engine(KafkaAuditSink sink) {
        return RuleStone.builder().decision(DecisionTableJson.read(TABLE)).auditSink(sink).build();
    }

    private void bufferAvailable(double bytes) {
        MetricName name = new MetricName("buffer-available-bytes", "producer-metrics", "", Map.of());
        producer.setMockMetrics(name, new Metric() {
            @Override
            public MetricName metricName() {
                return name;
            }

            @Override
            public Object metricValue() {
                return bytes;
            }
        });
    }

    @Test
    void recordsArePublishedAsJsonKeyedByCorrelationId() {
        bufferAvailable(32 << 20);
        RuleStoneEngine engine = engine(KafkaAuditSink.of(producer, "rulestone.decisions"));

        DecisionResult result = engine.evaluate(REF.correlationId("PAY-2026-000042"), FACTS);
        engine.evaluate(REF, FACTS);

        List<ProducerRecord<String, String>> sent = producer.history();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).topic()).isEqualTo("rulestone.decisions");
        assertThat(sent.get(0).key()).isEqualTo("PAY-2026-000042");
        assertThat(sent.get(0).value()).isEqualTo(DecisionRecordJson.write(result.record()));
        assertThat(sent.get(1).key()).isNull();
    }

    @Test
    void aFullBufferFailsTheEvaluationAtOnce() {
        bufferAvailable(512 * 1024);
        RuleStoneEngine engine = engine(KafkaAuditSink.of(producer, "rulestone.decisions"));

        assertThatThrownBy(() -> engine.evaluate(REF, FACTS))
                .isInstanceOf(AuditException.class)
                .cause().isInstanceOf(BufferExhaustedException.class)
                .hasMessageStartingWith("Kafka producer has 524288 bytes of buffer free, less than the 1048576");
        assertThat(producer.history()).isEmpty();
    }

    @Test
    void theFreeBufferRequiredCanBeSet() {
        bufferAvailable(512 * 1024);
        RuleStoneEngine engine = engine(KafkaAuditSink.builder(producer, "rulestone.decisions")
                .failWhenBufferBelow(64 * 1024).build());

        engine.evaluate(REF, FACTS);

        assertThat(producer.history()).hasSize(1);
    }

    @Test
    void failingFastNeedsAProducerThatReportsItsBuffer() {
        assertThatThrownBy(() -> KafkaAuditSink.of(producer, "rulestone.decisions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("producer-metrics/buffer-available-bytes")
                .hasMessageContaining("blockWhenBufferFull()");

        RuleStoneEngine engine = engine(KafkaAuditSink.builder(producer, "rulestone.decisions")
                .blockWhenBufferFull().build());
        engine.evaluate(REF, FACTS);
        assertThat(producer.history()).hasSize(1);
    }

    @Test
    void aRecordTheProducerRefusesAtOnceFailsTheEvaluation() {
        // As KafkaProducer does when its buffer stays full or the topic's metadata cannot be
        // fetched within max.block.ms: it returns a failed future instead of throwing.
        MockProducer<String, String> refusing = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
                TimeoutException e = new TimeoutException("Topic rulestone.decisions not present in metadata after 5 ms.");
                callback.onCompletion(null, e);
                return CompletableFuture.failedFuture(e);
            }
        };
        KafkaAuditSink sink = KafkaAuditSink.builder(refusing, "rulestone.decisions").blockWhenBufferFull().build();

        assertThatThrownBy(() -> engine(sink).evaluate(REF, FACTS))
                .isInstanceOf(AuditException.class)
                .cause().isInstanceOf(TimeoutException.class);
        assertThat(sink.deliveryFailures()).isZero();
    }

    @Test
    void aProducerThatThrowsFailsTheEvaluation() {
        producer.close();
        RuleStoneEngine engine = engine(KafkaAuditSink.builder(producer, "rulestone.decisions")
                .blockWhenBufferFull().build());

        assertThatThrownBy(() -> engine.evaluate(REF, FACTS)).isInstanceOf(AuditException.class);
    }

    @Test
    void aRecordAcceptedButNotDeliveredIsReportedAndCounted() throws Exception {
        MockProducer<String, String> manual = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        List<String> failed = new ArrayList<>();
        KafkaAuditSink sink = KafkaAuditSink.builder(manual, "rulestone.decisions")
                .blockWhenBufferFull()
                .onDeliveryFailure((recordId, e) -> failed.add(recordId + ": " + e.getClass().getSimpleName()))
                .build();

        DecisionResult result = engine(sink).evaluate(REF, FACTS);
        assertThat(result.output()).containsEntry("action", "BLOCK");

        // Delivery fails later, on the producer's I/O thread.
        Thread io = new Thread(() -> manual.errorNext(new TimeoutException("delivery timed out")));
        io.start();
        io.join();

        assertThat(sink.deliveryFailures()).isEqualTo(1);
        assertThat(failed).containsExactly(result.record().recordId() + ": TimeoutException");
    }

    @Test
    void configurationIsValidated() {
        assertThatThrownBy(() -> KafkaAuditSink.builder(producer, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("topic must not be blank");
        assertThatThrownBy(() -> KafkaAuditSink.builder(producer, "t").failWhenBufferBelow(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("bytes must be positive");
    }

    /** As in docs/java-api.md. */
    @Test
    void guideExample() {
        bufferAvailable(32 << 20);
        Producer<String, String> producer = this.producer;
        List<DecisionTable> tables = List.of(DecisionTableJson.read(TABLE));
        List<String> undelivered = new ArrayList<>();

        RuleStoneEngine engine = RuleStone.builder()
                .decisions(tables)
                .auditSink(KafkaAuditSink.builder(producer, "rulestone.decisions")
                        .failWhenBufferBelow(1 << 20)   // fail at once when less than 1 MiB of buffer is free
                        .onDeliveryFailure((recordId, e) -> undelivered.add(recordId))
                        .build())
                .build();

        engine.evaluate(REF, FACTS);
        assertThat(this.producer.history()).hasSize(1);
        assertThat(undelivered).isEmpty();
    }
}
