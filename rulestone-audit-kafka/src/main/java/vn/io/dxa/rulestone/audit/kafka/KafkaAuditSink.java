//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.audit.kafka;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.producer.BufferExhaustedException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Metric;
import vn.io.dxa.rulestone.api.AuditSink;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.core.DecisionRecordJson;

/**
 * Publishes every {@link DecisionRecord} to a Kafka topic, as one line of JSON written by
 * {@link DecisionRecordJson}, keyed by its correlation id so that the records of one business
 * transaction stay in order on one partition. Records without a correlation id have no key.
 *
 * <pre>{@code
 * Producer<String, String> producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
 * RuleStoneEngine engine = RuleStone.builder()
 *     .decisions(tables)
 *     .auditSink(KafkaAuditSink.of(producer, "rulestone.decisions"))
 *     .build();
 * }</pre>
 *
 * <p><b>What "accepted" means.</b> {@link #write} hands the record to the producer's buffer and
 * returns; it does not wait for the broker. So a record is accepted once the producer has
 * taken it, and {@link vn.io.dxa.rulestone.api.AuditException} reaches the caller only when the
 * producer refuses it at once: its buffer is full, the topic's metadata cannot be fetched in
 * time, or the producer is closed. A record that the producer took but then fails to deliver,
 * after its own retries and {@code delivery.timeout.ms}, is reported to
 * {@link Builder#onDeliveryFailure} and counted in {@link #deliveryFailures()}; by then the
 * decision has been returned. Configure the producer for durability: {@code acks=all} and
 * {@code enable.idempotence=true}, the defaults.
 *
 * <p><b>When the buffer is full.</b> By default the sink fails the evaluation at once when the
 * producer has less than {@link Builder#failWhenBufferBelow} bytes of buffer free, which happens
 * when the brokers cannot keep up or cannot be reached. {@link Builder#blockWhenBufferFull()}
 * instead lets the producer wait for space, for up to its {@code max.block.ms}. For an inline
 * path, failing fast is safer: the caller's fallback decides, instead of a payment waiting on
 * the audit log. In both cases {@code max.block.ms} is the longest {@link #write} can block, also
 * while the producer fetches the topic's metadata after it starts; set it to what the caller can
 * afford.
 *
 * <p>The sink does not own the producer: close the producer when the application stops, after
 * the engine is no longer used, so that buffered records are sent. Thread-safe, as is the
 * producer.
 */
public final class KafkaAuditSink implements AuditSink {

    /** Default of {@link Builder#failWhenBufferBelow}: 1 MiB. */
    public static final long DEFAULT_MIN_FREE_BUFFER = 1L << 20;

    private static final String BUFFER_AVAILABLE = "buffer-available-bytes";
    private static final String PRODUCER_METRICS = "producer-metrics";

    private final Producer<String, String> producer;
    private final String topic;
    private final Metric bufferAvailable;
    private final long minFreeBuffer;
    private final BiConsumer<String, Exception> onDeliveryFailure;
    private final AtomicLong deliveryFailures = new AtomicLong();

    private KafkaAuditSink(Builder builder) {
        this.producer = builder.producer;
        this.topic = builder.topic;
        this.minFreeBuffer = builder.minFreeBuffer;
        this.onDeliveryFailure = builder.onDeliveryFailure;
        this.bufferAvailable = builder.failFast ? bufferMetric(producer) : null;
    }

    /** A sink with the defaults: fails fast when less than 1 MiB of producer buffer is free. */
    public static KafkaAuditSink of(Producer<String, String> producer, String topic) {
        return builder(producer, topic).build();
    }

    /**
     * @param producer a producer with {@code String} keys and values, e.g. {@code StringSerializer} for both
     * @param topic    the topic to publish records to
     */
    public static Builder builder(Producer<String, String> producer, String topic) {
        return new Builder(producer, topic);
    }

    /**
     * Hands the record to the producer without waiting for the broker.
     *
     * @throws BufferExhaustedException if the producer's buffer is (almost) full and the sink fails fast
     * @throws KafkaException           if the producer refuses the record at once, e.g. because the topic's
     *                                  metadata could not be fetched within {@code max.block.ms}
     */
    @Override
    public void write(DecisionRecord record) {
        String value = DecisionRecordJson.write(record);
        if (bufferAvailable != null) {
            double free = ((Number) bufferAvailable.metricValue()).doubleValue();
            if (free < minFreeBuffer + value.length() * 3L) {   // at most 3 UTF-8 bytes per char
                throw new BufferExhaustedException("Kafka producer has " + (long) free + " bytes of buffer free, "
                        + "less than the " + minFreeBuffer + " the audit sink requires; decision record "
                        + record.recordId() + " not published");
            }
        }
        String recordId = record.recordId();
        Thread caller = Thread.currentThread();
        Future<RecordMetadata> sent = producer.send(
                new ProducerRecord<>(topic, record.correlationId().orElse(null), value),
                (metadata, e) -> {
                    // On the caller's thread, the producer refused the record at once: see below.
                    if (e != null && Thread.currentThread() != caller) {
                        deliveryFailures.incrementAndGet();
                        onDeliveryFailure.accept(recordId, e);
                    }
                });
        // KafkaProducer reports a full buffer or missing metadata through an already failed future,
        // not an exception: the record was refused, and the evaluation must not count it as accepted.
        if (sent.isDone()) {
            try {
                sent.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof KafkaException kafka
                        ? kafka
                        : new KafkaException("Kafka producer refused decision record " + recordId, e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KafkaException("interrupted while publishing decision record " + recordId, e);
            }
        }
    }

    /**
     * Records the producer accepted but could not deliver, since this sink was created. Monitor
     * it: each one is a decision whose record did not reach the topic.
     */
    public long deliveryFailures() {
        return deliveryFailures.get();
    }

    private static Metric bufferMetric(Producer<String, String> producer) {
        return producer.metrics().entrySet().stream()
                .filter(e -> e.getKey().name().equals(BUFFER_AVAILABLE) && e.getKey().group().equals(PRODUCER_METRICS))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the producer reports no " + PRODUCER_METRICS + "/"
                        + BUFFER_AVAILABLE + " metric, so the sink cannot tell when its buffer is full; "
                        + "use blockWhenBufferFull()"));
    }

    /** Configures a {@link KafkaAuditSink}. */
    public static final class Builder {

        private final Producer<String, String> producer;
        private final String topic;
        private boolean failFast = true;
        private long minFreeBuffer = DEFAULT_MIN_FREE_BUFFER;
        private BiConsumer<String, Exception> onDeliveryFailure = (recordId, e) -> { };

        private Builder(Producer<String, String> producer, String topic) {
            this.producer = Objects.requireNonNull(producer, "producer");
            this.topic = Objects.requireNonNull(topic, "topic");
            if (topic.isBlank()) {
                throw new IllegalArgumentException("topic must not be blank");
            }
        }

        /**
         * Fails the evaluation at once when the producer has less than {@code bytes} of buffer
         * ({@code buffer.memory}) free, plus room for the record. The default, 1 MiB. Use at least
         * the producer's {@code batch.size}, since a new batch takes that much at once.
         *
         * @param bytes a positive number of bytes, well below the producer's {@code buffer.memory}
         */
        public Builder failWhenBufferBelow(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("bytes must be positive");
            }
            this.failFast = true;
            this.minFreeBuffer = bytes;
            return this;
        }

        /** Lets the producer wait for buffer space, for up to its {@code max.block.ms}, instead of failing fast. */
        public Builder blockWhenBufferFull() {
            this.failFast = false;
            return this;
        }

        /**
         * Called with the record id and the exception when the producer gives up delivering a
         * record it accepted. It runs on the producer's I/O thread and must return quickly.
         */
        public Builder onDeliveryFailure(BiConsumer<String, Exception> onDeliveryFailure) {
            this.onDeliveryFailure = Objects.requireNonNull(onDeliveryFailure, "onDeliveryFailure");
            return this;
        }

        /**
         * @throws IllegalArgumentException if the sink fails fast and the producer does not report
         *         how much buffer it has free
         */
        public KafkaAuditSink build() {
            return new KafkaAuditSink(this);
        }
    }
}
