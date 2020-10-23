/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2020 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.agent.kafka;

import co.elastic.apm.agent.AbstractInstrumentationTest;
import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.configuration.MessagingConfiguration;
import co.elastic.apm.agent.impl.context.Destination;
import co.elastic.apm.agent.impl.context.Message;
import co.elastic.apm.agent.impl.context.SpanContext;
import co.elastic.apm.agent.impl.context.TransactionContext;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.matcher.WildcardMatcher;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Tests newer client with a 0.10.2.2 version.
 * This test is disabled because may fail on CI, maybe because of running in parallel to the current broker test.
 * It is still useful to run locally to test the legacy broker.
 * <p>
 * Each test sends a message to a request topic and waits on a reply message. This serves two purposes:
 * 1.  reduce waits to a minimum within tests
 * 2.  test both consumer instrumentation functionalities:
 * a.  the poll span creation (as part of the test, occurring within a traced transaction)- one per poll action
 * b.  the creation of consumer transaction- one per consumed record
 */
@SuppressWarnings("NotNullFieldNotInitialized")
@Disabled
public class KafkaLegacyBrokerIT extends AbstractInstrumentationTest {

    static final String REQUEST_TOPIC = UUID.randomUUID().toString();
    static final String REPLY_TOPIC = UUID.randomUUID().toString();
    static final String REQUEST_KEY = "request-key";
    static final String REPLY_KEY = "response-key";
    public static final String FIRST_MESSAGE_VALUE = "First message body";
    public static final String SECOND_MESSAGE_VALUE = "Second message body";

    private static KafkaContainer kafka;
    private static String bootstrapServers;
    private static Consumer consumerThread;
    private static KafkaConsumer<String, String> replyConsumer;
    private static KafkaProducer<String, String> producer;

    private final CoreConfiguration coreConfiguration;
    private final MessagingConfiguration messagingConfiguration;

    private TestScenario testScenario;

    public KafkaLegacyBrokerIT() {
        this.coreConfiguration = config.getConfig(CoreConfiguration.class);
        this.messagingConfiguration = config.getConfig(MessagingConfiguration.class);
    }

    @BeforeAll
    public static void setup() {
        reporter.disableDestinationAddressCheck();

        // confluent versions 3.2.x correspond Kafka versions 0.10.2.2 -
        // https://docs.confluent.io/current/installation/versions-interoperability.html#cp-and-apache-ak-compatibility
        kafka = new KafkaContainer("3.2.2");
        kafka.start();
        bootstrapServers = kafka.getBootstrapServers();
        consumerThread = new Consumer();
        consumerThread.start();
        replyConsumer = createKafkaConsumer();
        replyConsumer.subscribe(Collections.singletonList(REPLY_TOPIC));
        producer = new KafkaProducer<>(
            ImmutableMap.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, UUID.randomUUID().toString(),
                // This should guarantee that records are batched, as long as they are sent within the configured duration
                ProducerConfig.LINGER_MS_CONFIG, 50
            ),
            new StringSerializer(),
            new StringSerializer()
        );
    }

    @AfterAll
    public static void tearDown() {
        producer.close();
        replyConsumer.unsubscribe();
        replyConsumer.close();
        consumerThread.terminate();
        kafka.stop();
    }

    @BeforeEach
    public void startTransaction() {
        Transaction transaction = tracer.startRootTransaction(null).activate();
        transaction.withName("Kafka-Test Transaction");
        transaction.withType("request");
        transaction.withResult("success");
        testScenario = TestScenario.NORMAL;
    }

    @AfterEach
    public void endTransaction() {
        Transaction currentTransaction = tracer.currentTransaction();
        if (currentTransaction != null) {
            currentTransaction.deactivate().end();
        }
    }

    @Test
    public void testSendTwoRecords_IterableFor() {
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_FOR);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_IterableForEach() {
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_FOREACH);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_IterableSpliterator() {
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_SPLITERATOR);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_RecordsIterable() {
        consumerThread.setIterationMode(RecordIterationMode.RECORDS_ITERABLE);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_RecordListIterableFor() {
        consumerThread.setIterationMode(RecordIterationMode.RECORD_LIST_ITERABLE_FOR);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_RecordListIterableForEach() {
        consumerThread.setIterationMode(RecordIterationMode.RECORD_LIST_ITERABLE_FOREACH);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_RecordListSubList() {
        consumerThread.setIterationMode(RecordIterationMode.RECORD_LIST_SUB_LIST);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testSendTwoRecords_PartiallyIterate() {
        // Here we test that the KafkaConsumer#poll instrumentation will end transactions that are left open
        consumerThread.setIterationMode(RecordIterationMode.PARTIALLY_ITERATE);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testBodyCaptureEnabled() {
        doReturn(CoreConfiguration.EventType.ALL).when(coreConfiguration).getCaptureBody();
        testScenario = TestScenario.BODY_CAPTURE_ENABLED;
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_FOR);
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testDestinationAddressCollectionDisabled() {
        when(messagingConfiguration.shouldCollectQueueAddress()).thenReturn(false);
        testScenario = TestScenario.TOPIC_ADDRESS_COLLECTION_DISABLED;
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_FOR);
        reporter.disableDestinationAddressCheck();
        sendTwoRecordsAndConsumeReplies();
        verifyTracing();
    }

    @Test
    public void testIgnoreTopic() {
        when(messagingConfiguration.getIgnoreMessageQueues()).thenReturn(List.of(WildcardMatcher.valueOf(REQUEST_TOPIC)));
        testScenario = TestScenario.IGNORE_REQUEST_TOPIC;
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_FOR);
        sendTwoRecordsAndConsumeReplies();

        // we expect only one span for polling the reply topic
        List<Span> spans = reporter.getSpans();
        assertThat(spans).hasSize(1);
        verifyPollSpanContents(spans.get(0));
        List<Transaction> transactions = reporter.getTransactions();
        assertThat(transactions).isEmpty();
    }

    @Test
    public void testTransactionCreationWithoutContext() {
        testScenario = TestScenario.NO_CONTEXT_PROPAGATION;
        consumerThread.setIterationMode(RecordIterationMode.ITERABLE_FOR);
        //noinspection ConstantConditions
        tracer.currentTransaction().deactivate().end();
        reporter.reset();

        // Send without context
        sendTwoRecordsAndConsumeReplies();

        // We expect two transactions from records read from the request topic, each creating a send span as well.
        // In addition we expect two transactions from the main test thread, iterating over reply messages.
        List<Span> spans = reporter.getSpans();
        assertThat(spans).hasSize(2);
        Span sendSpan1 = spans.get(0);
        verifySendSpanContents(sendSpan1, REPLY_TOPIC);
        Span sendSpan2 = spans.get(1);
        verifySendSpanContents(sendSpan2, REPLY_TOPIC);
        List<Transaction> transactions = reporter.getTransactions();
        assertThat(transactions).hasSize(4);
        verifyKafkaTransactionContents(transactions.get(0), null, null, REQUEST_TOPIC);
        verifyKafkaTransactionContents(transactions.get(1), null, null, REQUEST_TOPIC);
        verifyKafkaTransactionContents(transactions.get(2), sendSpan1, null, REPLY_TOPIC);
        verifyKafkaTransactionContents(transactions.get(3), sendSpan2, null, REPLY_TOPIC);
    }

    private void sendTwoRecordsAndConsumeReplies() {
        final StringBuilder callback = new StringBuilder();
        ProducerRecord<String, String> record1 = new ProducerRecord<>(REQUEST_TOPIC, 0, REQUEST_KEY, FIRST_MESSAGE_VALUE);
        ProducerRecord<String, String> record2 = new ProducerRecord<>(REQUEST_TOPIC, REQUEST_KEY, SECOND_MESSAGE_VALUE);
        producer.send(record1);
        producer.send(record2, (metadata, exception) -> callback.append("done"));
        if (testScenario != TestScenario.IGNORE_REQUEST_TOPIC) {
            await().atMost(2000, MILLISECONDS).until(() -> reporter.getTransactions().size() == 2);
            int expectedSpans = (testScenario == TestScenario.NO_CONTEXT_PROPAGATION) ? 2 : 4;
            await().atMost(500, MILLISECONDS).until(() -> reporter.getSpans().size() == expectedSpans);
        }
        //noinspection deprecation - this poll overload is deprecated in newer clients, but enables testing of old ones
        ConsumerRecords<String, String> replies = replyConsumer.poll(2000);
        assertThat(callback).isNotEmpty();
        assertThat(replies.count()).isEqualTo(2);
        Iterator<ConsumerRecord<String, String>> iterator = replies.iterator();
        assertThat(iterator.next().value()).isEqualTo(FIRST_MESSAGE_VALUE);
        assertThat(iterator.next().value()).isEqualTo(SECOND_MESSAGE_VALUE);
        // this is required in order to end transactions related to the record iteration
        assertThat(iterator.hasNext()).isFalse();
    }

    private void verifyTracing() {
        List<Span> spans = reporter.getSpans();
        // we expect two send spans to request topic, two send spans to reply topic and one poll span from reply topic
        assertThat(spans).hasSize(5);
        Span sendRequestSpan0 = spans.get(0);
        verifySendSpanContents(sendRequestSpan0, REQUEST_TOPIC);
        Span sendRequestSpan1 = spans.get(1);
        verifySendSpanContents(sendRequestSpan1, REQUEST_TOPIC);
        Span sendReplySpan0 = spans.get(2);
        verifySendSpanContents(sendReplySpan0, REPLY_TOPIC);
        Span sendReplySpan1 = spans.get(3);
        verifySendSpanContents(sendReplySpan1, REPLY_TOPIC);

        List<Transaction> transactions = reporter.getTransactions();
        assertThat(transactions).hasSize(2);
        verifyKafkaTransactionContents(transactions.get(0), sendRequestSpan0, FIRST_MESSAGE_VALUE, REQUEST_TOPIC);
        verifyKafkaTransactionContents(transactions.get(1), sendRequestSpan1, SECOND_MESSAGE_VALUE, REQUEST_TOPIC);

        Span pollSpan = spans.get(4);
        verifyPollSpanContents(pollSpan);
    }

    private void verifyPollSpanContents(Span pollSpan) {
        assertThat(pollSpan.getType()).isEqualTo("messaging");
        assertThat(pollSpan.getSubtype()).isEqualTo("kafka");
        assertThat(pollSpan.getAction()).isEqualTo("poll");
        assertThat(pollSpan.getNameAsString()).isEqualTo("KafkaConsumer#poll");
        Destination.Service service = pollSpan.getContext().getDestination().getService();
        assertThat(service.getType()).isEqualTo("messaging");
        assertThat(service.getResource().toString()).isEqualTo("kafka");
        assertThat(service.getName().toString()).isEqualTo("kafka");
    }

    private void verifySendSpanContents(Span sendSpan, String topicName) {
        assertThat(sendSpan.getType()).isEqualTo("messaging");
        assertThat(sendSpan.getSubtype()).isEqualTo("kafka");
        assertThat(sendSpan.getAction()).isEqualTo("send");
        assertThat(sendSpan.getNameAsString()).isEqualTo("KafkaProducer#send to " + topicName);
        SpanContext context = sendSpan.getContext();
        Message message = context.getMessage();
        assertThat(message.getQueueName()).isEqualTo(topicName);
        Destination.Service service = context.getDestination().getService();
        assertThat(service.getType()).isEqualTo("messaging");
        assertThat(service.getResource().toString()).isEqualTo("kafka/" + topicName);
        assertThat(service.getName().toString()).isEqualTo("kafka");
    }

    private void verifyKafkaTransactionContents(Transaction transaction, @Nullable Span parentSpan,
                                                @Nullable String messageValue, String topic) {
        assertThat(transaction.getType()).isEqualTo("messaging");
        assertThat(transaction.getNameAsString()).isEqualTo("Kafka record from " + topic);
        TraceContext traceContext = transaction.getTraceContext();
        if (parentSpan != null) {
            assertThat(traceContext.getTraceId()).isNotEqualTo(parentSpan.getTraceContext().getTraceId());
            assertThat(traceContext.getParentId()).isNotEqualTo(parentSpan.getTraceContext().getId());
        }
        TransactionContext transactionContext = transaction.getContext();
        Message message = transactionContext.getMessage();
        assertThat(message.getAge()).isGreaterThanOrEqualTo(0);
        assertThat(message.getQueueName()).isEqualTo(topic);
        if (testScenario == TestScenario.BODY_CAPTURE_ENABLED && messageValue != null) {
            String messageBody = "key=" + REQUEST_KEY + "; value=" + messageValue;
            StringBuilder body = message.getBodyForRead();
            assertThat(body).isNotNull();
            assertThat(messageBody).isEqualTo(body.toString());
        } else {
            assertThat(message.getBodyForRead()).isNull();
        }
    }

    static KafkaConsumer<String, String> createKafkaConsumer() {
        return new KafkaConsumer<>(
            ImmutableMap.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "tc-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
            ),
            new StringDeserializer(),
            new StringDeserializer()
        );
    }

    static class Consumer extends Thread {
        private volatile boolean running;
        private volatile RecordIterationMode iterationMode;

        @Override
        public synchronized void start() {
            running = true;
            super.start();
        }

        void setIterationMode(RecordIterationMode iterationMode) {
            this.iterationMode = iterationMode;
        }

        public synchronized void terminate() {
            running = false;
            this.interrupt();
        }

        @Override
        public void run() {
            KafkaConsumer<String, String> kafkaConsumer = createKafkaConsumer();
            kafkaConsumer.subscribe(Collections.singletonList(REQUEST_TOPIC));
            while (running) {
                try {
                    //noinspection deprecation - this poll overload is deprecated in newer clients, but enables testing of old ones
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(100);
                    if (records != null && !records.isEmpty()) {
                        // Can't use switch because we run this test in a dedicated class loader, where the anonymous
                        // class created by the enum switch cannot be loaded
                        if (iterationMode == RecordIterationMode.ITERABLE_FOR) {
                            for (ConsumerRecord<String, String> record : records) {
                                producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
                            }
                        } else if (iterationMode == RecordIterationMode.ITERABLE_FOREACH) {
                            records.forEach(new ConsumerRecordConsumer());
                        } else if (iterationMode == RecordIterationMode.ITERABLE_SPLITERATOR) {
                            records.spliterator().forEachRemaining(new ConsumerRecordConsumer());
                        } else if (iterationMode == RecordIterationMode.RECORDS_ITERABLE) {
                            for (ConsumerRecord<String, String> record : records.records(REQUEST_TOPIC)) {
                                producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
                            }
                        } else if (iterationMode == RecordIterationMode.RECORD_LIST_ITERABLE_FOR) {
                            List<ConsumerRecord<String, String>> recordList = records.records(records.partitions().iterator().next());
                            for (ConsumerRecord<String, String> record : recordList) {
                                producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
                            }
                        } else if (iterationMode == RecordIterationMode.RECORD_LIST_SUB_LIST) {
                            List<ConsumerRecord<String, String>> recordList = records.records(records.partitions().iterator().next());
                            for (ConsumerRecord<String, String> record : recordList.subList(0, 2)) {
                                producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
                            }
                        } else if (iterationMode == RecordIterationMode.RECORD_LIST_ITERABLE_FOREACH) {
                            List<ConsumerRecord<String, String>> recordList = records.records(records.partitions().iterator().next());
                            recordList.forEach(new ConsumerRecordConsumer());
                        } else if (iterationMode == RecordIterationMode.PARTIALLY_ITERATE) {
                            // we should normally get a batch of two, but may get one in two different polls
                            List<ConsumerRecord<String, String>> recordList = records.records(records.partitions().iterator().next());
                            Iterator<ConsumerRecord<String, String>> iterator = recordList.iterator();
                            ConsumerRecord<String, String> record = iterator.next();
                            producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
                            if (recordList.size() == 2) {
                                record = iterator.next();
                                producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!(e instanceof InterruptException)) {
                        System.err.println("Kafka consumer failure: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
            kafkaConsumer.unsubscribe();
            kafkaConsumer.close();
        }
    }

    enum RecordIterationMode {
        ITERABLE_FOR,
        ITERABLE_FOREACH,
        ITERABLE_SPLITERATOR,
        RECORD_LIST_ITERABLE_FOR,
        RECORD_LIST_ITERABLE_FOREACH,
        RECORD_LIST_SUB_LIST,
        RECORDS_ITERABLE,
        PARTIALLY_ITERATE
    }

    enum TestScenario {
        NORMAL,
        BODY_CAPTURE_ENABLED,
        IGNORE_REQUEST_TOPIC,
        NO_CONTEXT_PROPAGATION,
        TOPIC_ADDRESS_COLLECTION_DISABLED
    }

    /**
     * Must implement explicitly in order to use the dependency injection runner
     */
    static class ConsumerRecordConsumer implements java.util.function.Consumer<ConsumerRecord<String, String>> {
        @Override
        public void accept(ConsumerRecord<String, String> record) {
            producer.send(new ProducerRecord<>(REPLY_TOPIC, REPLY_KEY, record.value()));
        }
    }
}
