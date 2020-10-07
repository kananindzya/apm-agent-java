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
package co.elastic.apm.agent.concurrent;

import co.elastic.apm.agent.AbstractInstrumentationTest;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class ExecutorServiceInstrumentationTest extends AbstractInstrumentationTest {

    private ExecutorService executor;
    private CurrentThreadExecutor currentThreadExecutor;
    private Transaction transaction;

    private void init(Supplier<ExecutorService> supplier) {
        executor = supplier.get();
    }

    public static Stream<Arguments> params() {
        List<Supplier<Executor>> params = Arrays.asList(Executors::newSingleThreadExecutor,
                Executors::newSingleThreadScheduledExecutor,
                ForkJoinPool::new
        );
        return params.stream().map(k -> Arguments.of(k)).collect(Collectors.toList()).stream();
    }

    @BeforeEach
    public void setUp() {
        currentThreadExecutor = new CurrentThreadExecutor();
        transaction = tracer.startRootTransaction(null).withName("Transaction").activate();
    }

    @AfterEach
    public void tearDown() {
        transaction.deactivate().end();
        assertThat(JavaConcurrent.needsContext.get()).isNotEqualTo(false);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testExecutorSubmitRunnableAnonymousInnerClass(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.submit(new Runnable() {
            @Override
            public void run() {
                createAsyncSpan();
            }
        }).get();

        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testExecutorSubmitRunnableLambda(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.submit(() -> createAsyncSpan()).get(1, TimeUnit.SECONDS);
        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testExecutorExecute(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.execute(this::createAsyncSpan);
        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testExecutorSubmitRunnableWithResult(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.submit(this::createAsyncSpan, null);
        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testExecutorSubmitCallableMethodReference(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.submit(() -> {
            createAsyncSpan();
            return null;
        }).get(1, TimeUnit.SECONDS);
        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testInvokeAll(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        final List<Future<Span>> futures = executor.invokeAll(Arrays.<Callable<Span>>asList(this::createAsyncSpan, () -> createAsyncSpan(), new Callable<Span>() {
            @Override
            public Span call() throws Exception {
                return createAsyncSpan();
            }
        }));
        futures.forEach(ThrowingConsumer.of(Future::get));
        reporter.awaitSpanCount(3);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testNestedExecutions(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        currentThreadExecutor.execute(() -> executor.execute(this::createAsyncSpan));
        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testInvokeAllTimed(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        final List<Future<Span>> futures = executor.invokeAll(Arrays.asList(
                new Callable<Span>() {
                    @Override
                    public Span call() throws Exception {
                        return createAsyncSpan();
                    }
                },
                new Callable<Span>() {
                    @Override
                    public Span call() throws Exception {
                        return createAsyncSpan();
                    }
                }), 1, TimeUnit.SECONDS);
        futures.forEach(ThrowingConsumer.of(Future::get));
        reporter.awaitSpanCount(2);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testInvokeAny(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.invokeAny(Collections.singletonList(new Callable<Span>() {
            @Override
            public Span call() {
                return createAsyncSpan();
            }
        }));
        reporter.awaitSpanCount(1);
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testInvokeAnyTimed(Supplier<ExecutorService> serviceSupplier) throws Exception {
        init(serviceSupplier);

        executor.invokeAny(Collections.<Callable<Span>>singletonList(new Callable<Span>() {
            @Override
            public Span call() {
                return createAsyncSpan();
            }
        }), 1, TimeUnit.SECONDS);
        reporter.awaitSpanCount(1);
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        static <T> Consumer<T> of(ThrowingConsumer<T> throwingConsumer) {
            return t -> {
                try {
                    throwingConsumer.accept(t);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        }

        void accept(T t) throws Exception;
    }

    private Span createAsyncSpan() {
        assertThat(tracer.getActive()).isNotNull();
        assertThat(tracer.getActive().getTraceContext().getId()).isEqualTo(transaction.getTraceContext().getId());
        final Span span = tracer.getActive().createSpan().withName("Async");
        span.end();
        return span;
    }
}
