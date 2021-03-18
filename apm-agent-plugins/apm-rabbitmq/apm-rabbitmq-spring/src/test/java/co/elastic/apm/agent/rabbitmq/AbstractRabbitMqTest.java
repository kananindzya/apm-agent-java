/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2021 Elastic and contributors
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
package co.elastic.apm.agent.rabbitmq;

import co.elastic.apm.agent.AbstractInstrumentationTest;
import co.elastic.apm.agent.bci.ElasticApmAgent;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.Transaction;
import org.junit.AfterClass;
import org.junit.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;


import java.util.List;

import static co.elastic.apm.agent.rabbitmq.RabbitMQTest.checkParentChild;
import static co.elastic.apm.agent.rabbitmq.RabbitMQTest.checkSendSpan;
import static co.elastic.apm.agent.rabbitmq.RabbitMQTest.checkTransaction;
import static co.elastic.apm.agent.rabbitmq.RabbitMQTest.getNonRootTransaction;
import static co.elastic.apm.agent.rabbitmq.TestConstants.TOPIC_EXCHANGE_NAME;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public abstract class AbstractRabbitMqTest extends AbstractInstrumentationTest {

    private static RabbitMQContainer container;

    private static final String MESSAGE = "foo-bar";

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
            container = new RabbitMQContainer(TestConstants.DOCKER_TESTCONTAINER_RABBITMQ_IMAGE);
            container.withExtraHost("localhost", "127.0.0.1");
            container.start();

            TestPropertyValues.of(
                "spring.rabbitmq.host=" + container.getHost(),
                "spring.rabbitmq.port=" + container.getAmqpPort(),
                "spring.rabbitmq.username=" + container.getAdminUsername(),
                "spring.rabbitmq.password=" + container.getAdminPassword())
                .applyTo(configurableApplicationContext.getEnvironment());
        }
    }

    @AfterClass
    public static void after() {
        container.close();
        ElasticApmAgent.reset();
    }

    @Autowired
    public RabbitTemplate rabbitTemplate;

    @Test
    public void verifyThatTransactionWithSpanCreated() {
        Transaction rootTransaction = startTestRootTransaction("Rabbit-Test Root Transaction");
        rabbitTemplate.convertAndSend(TOPIC_EXCHANGE_NAME, TestConstants.ROUTING_KEY, MESSAGE);
        rootTransaction.deactivate().end();

        getReporter().awaitTransactionCount(2);
        getReporter().awaitSpanCount(2);

        Transaction receiveTransaction = getNonRootTransaction(rootTransaction, getReporter().getTransactions());
        checkTransaction(receiveTransaction, TOPIC_EXCHANGE_NAME, "Spring AMQP");
        assertThat(receiveTransaction.getSpanCount().getTotal().get()).isEqualTo(1);

        List<Span> spans = getReporter().getSpans();
        Span sendSpan = spans.get(0);
        checkSendSpan(sendSpan, TOPIC_EXCHANGE_NAME, container.getHost(), container.getAmqpPort());
        checkParentChild(sendSpan, receiveTransaction);

        Span testSpan = spans.get(1);
        assertThat(testSpan.getNameAsString()).isEqualTo("testSpan");
        checkParentChild(receiveTransaction, testSpan);
    }

    @Test
    public void verifyTransactionWithDefaultExchangeName() {
        Transaction rootTransaction = startTestRootTransaction("Rabbit-Test Root Transaction");
        rabbitTemplate.convertAndSend(TestConstants.QUEUE_NAME, MESSAGE);
        rootTransaction.deactivate().end();

        getReporter().awaitTransactionCount(2);
        getReporter().awaitSpanCount(2);

        Transaction receiveTransaction = getNonRootTransaction(rootTransaction, getReporter().getTransactions());
        checkTransaction(receiveTransaction, "", "Spring AMQP");
        assertThat(receiveTransaction.getSpanCount().getTotal().get()).isEqualTo(1);
    }
}
