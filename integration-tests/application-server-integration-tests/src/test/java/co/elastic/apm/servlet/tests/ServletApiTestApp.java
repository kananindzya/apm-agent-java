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
package co.elastic.apm.servlet.tests;

import co.elastic.apm.servlet.AbstractServletContainerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.common.Json;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.assertj.core.util.Streams;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ServletApiTestApp extends TestApp {

    public ServletApiTestApp() {
        super("../simple-webapp", "simple-webapp.war", "/simple-webapp/status.jsp", "Simple Web App");
    }

    @Override
    public void test(AbstractServletContainerIntegrationTest test) throws Exception {
        testTransactionReporting(test);
        testTransactionErrorReporting(test);
        testSpanErrorReporting(test);
        testExecutorService(test);
        testExecuteCommandServlet(test);
        testHttpUrlConnection(test);
        testCaptureBody(test);
        testJmxMetrics(test);
        testTransactionReportingWithForward(test);
        testTransactionReportingWithInclude(test);
    }

    private void testCaptureBody(AbstractServletContainerIntegrationTest test) throws Exception {
        for (String readMethod : List.of("read-byte", "read-bytes", "read-offset", "read-line")) {
            test.clearMockServerLog();
            final Response response = test.getHttpClient().newCall(new Request.Builder()
                .post(RequestBody.create(MediaType.parse("text/plain"), "{foo}\n{bar}"))
                .url(test.getBaseUrl() + "/simple-webapp/echo?read-method=" + readMethod)
                .build())
                .execute();
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{foo}\n{bar}");

            final JsonNode transaction = test.assertTransactionReported("/simple-webapp/echo", 200);
            assertThat(transaction.get("context").get("request").get("body").textValue()).isEqualTo("{foo}\n{bar}");
        }
    }

    private void testTransactionReportingWithForward(AbstractServletContainerIntegrationTest test) throws Exception {
        String pathToTest = "/simple-webapp" + "/forward";
        boolean isExistForwardSpan = false;
        boolean isExistDbH2QuerySpan = false;
        test.clearMockServerLog();

        test.executeAndValidateRequest(pathToTest, "Hello World", 200, null);

        JsonNode transaction = test.assertTransactionReported(pathToTest, 200);

        List<JsonNode> reportedSpans = test.getReportedSpans();
        assertThat(reportedSpans.size()).isEqualTo(2);

        for (JsonNode span : test.getReportedSpans()) {
            String spanType = span.get("type").textValue();
            if ("servlet.request-dispatcher.forward".equals(spanType)) {
                isExistForwardSpan = true;
                assertThat(span.get("name").textValue()).isEqualTo("FORWARD /servlet");
            } else if ("db.h2.query".equals(spanType)) {
                isExistDbH2QuerySpan = true;
            }
        }
        assertThat(isExistForwardSpan).isEqualTo(true);
        assertThat(isExistDbH2QuerySpan).isEqualTo(true);
    }

    private void testTransactionReportingWithInclude(AbstractServletContainerIntegrationTest test) throws Exception {
        String pathToTest = "/simple-webapp" + "/include";
        boolean isExistIncludeSpan = false;
        boolean isExistDbH2QuerySpan = false;
        test.clearMockServerLog();

        test.executeAndValidateRequest(pathToTest, "Hello World", 200, null);

        JsonNode transaction = test.assertTransactionReported(pathToTest, 200);
        List<JsonNode> reportedSpans = test.getReportedSpans();
        assertThat(reportedSpans.size()).isEqualTo(2);

        for (JsonNode span : test.getReportedSpans()) {
            String spanType = span.get("type").textValue();

            if ("servlet.request-dispatcher.include".equals(spanType)) {
                isExistIncludeSpan = true;
                assertThat(span.get("name").textValue()).isEqualTo("INCLUDE /servlet");
            } else if ("db.h2.query".equals(spanType)) {
                isExistDbH2QuerySpan = true;
            }
        }
        assertThat(isExistIncludeSpan).isEqualTo(true);
        assertThat(isExistDbH2QuerySpan).isEqualTo(true);
    }

    private void testExecutorService(AbstractServletContainerIntegrationTest test) throws Exception {
        test.clearMockServerLog();
        final String pathToTest = "/simple-webapp/executor-service-servlet";
        test.executeAndValidateRequest(pathToTest, null, 200, null);
        String transactionId = test.assertTransactionReported(pathToTest, 200).get("id").textValue();
        final List<JsonNode> spans = test.assertSpansTransactionId(test::getReportedSpans, transactionId);
        assertThat(spans).hasSize(1);
    }

    private void testExecuteCommandServlet(AbstractServletContainerIntegrationTest test) throws Exception {
        testExecuteCommand(test, "?variant=WAIT_FOR");
        testExecuteCommand(test, "?variant=DESTROY");
    }

    private void testExecuteCommand(AbstractServletContainerIntegrationTest test, String queryPath) throws IOException, InterruptedException {
        test.clearMockServerLog();
        final String pathToTest = "/simple-webapp/execute-cmd-servlet";
        test.executeAndValidateRequest(pathToTest + queryPath, null, 200, null);
        String transactionId = test.assertTransactionReported(pathToTest, 200).get("id").textValue();
        List<JsonNode> spans = test.assertSpansTransactionId(test::getReportedSpans, transactionId);
        assertThat(spans).hasSize(1);

        for (JsonNode span : spans) {
            assertThat(span.get("parent_id").textValue()).isEqualTo(transactionId);
            assertThat(span.get("name").asText()).isEqualTo("java");
            assertThat(span.get("type").asText()).isEqualTo("process.java.execute");
        }
    }

    private void testHttpUrlConnection(AbstractServletContainerIntegrationTest test) throws IOException, InterruptedException {
        test.clearMockServerLog();
        final String pathToTest = "/simple-webapp/http-url-connection";
        test.executeAndValidateRequest(pathToTest, "Hello World!", 200, null);

        final List<JsonNode> reportedTransactions = test.getAllReported(test::getReportedTransactions, 2);
        final JsonNode innerTransaction = reportedTransactions.get(0);
        final JsonNode outerTransaction = reportedTransactions.get(1);

        final List<JsonNode> spans = test.assertSpansTransactionId(test::getReportedSpans, outerTransaction.get("id").textValue());
        assertThat(spans).hasSize(1);
        final JsonNode span = spans.get(0);

        assertThat(innerTransaction.get("trace_id").textValue()).isEqualTo(outerTransaction.get("trace_id").textValue());
        assertThat(innerTransaction.get("trace_id").textValue()).isEqualTo(span.get("trace_id").textValue());
        assertThat(innerTransaction.get("parent_id").textValue()).isEqualTo(span.get("id").textValue());
        assertThat(span.get("parent_id").textValue()).isEqualTo(outerTransaction.get("id").textValue());
        assertThat(span.get("context").get("http").get("url").textValue()).endsWith("hello-world.jsp");
        assertThat(span.get("context").get("http").get("status_code").intValue()).isEqualTo(200);
    }

    private void testTransactionReporting(AbstractServletContainerIntegrationTest test) throws Exception {
        for (String pathToTest : test.getPathsToTest()) {
            pathToTest = "/simple-webapp" + pathToTest;
            test.clearMockServerLog();
            test.executeAndValidateRequest(pathToTest, "Hello World", 200,
                Map.of("X-Forwarded-For", "123.123.123.123, 456.456.456.456"));
            JsonNode transaction = test.assertTransactionReported(pathToTest, 200);
            String transactionId = transaction.get("id").textValue();
            JsonNode context = transaction.get("context");
            assertThat(context).isNotNull();
            assertThat(context.get("service").get("framework").get("name").textValue()).isEqualTo("Servlet API");
            JsonNode request = context.get("request");
            assertThat(request).isNotNull();
            JsonNode headers = request.get("headers");
            assertThat(headers).isNotNull();
            JsonNode xForwardedForHeader = headers.get("X-Forwarded-For");
            if (xForwardedForHeader == null) {
                xForwardedForHeader = headers.get("X-Forwarded-For".toLowerCase());
            }
            assertThat(xForwardedForHeader).isNotNull();
            // I have no idea why, but it's too old to spend time on investigating...
            if (!test.getImageName().contains("jboss-eap-6")) {
                assertThat(xForwardedForHeader.textValue()).isEqualTo("123.123.123.123, 456.456.456.456");
            }
            JsonNode socket = request.get("socket");
            assertThat(socket).isNotNull();
            JsonNode remoteAddress = socket.get("remote_address");
            assertThat(remoteAddress).isNotNull();
            // Shockingly, not all servlet containers behave the same or even implement the Servlet spec...
            if (!"jboss".equals(test.getContainerName())) {
                assertThat(remoteAddress.textValue()).isNotEqualTo("123.123.123.123");
            }
            List<JsonNode> spans = test.assertSpansTransactionId(test::getReportedSpans, transactionId);
            for (JsonNode span : spans) {
                assertThat(span.get("type").textValue()).isEqualTo("db.h2.query");
            }
        }
    }

    private void testSpanErrorReporting(AbstractServletContainerIntegrationTest test) throws Exception {
        for (String pathToTest : test.getPathsToTest()) {
            pathToTest = "/simple-webapp" + pathToTest;
            test.clearMockServerLog();
            test.executeAndValidateRequest(pathToTest + "?cause_db_error=true", "DB Error", 200, null);
            JsonNode transaction = test.assertTransactionReported(pathToTest, 200);
            String transactionId = transaction.get("id").textValue();
            test.assertSpansTransactionId(test::getReportedSpans, transactionId);
            test.assertErrorContent(500, test::getReportedErrors, transactionId, "Column \"NON_EXISTING_COLUMN\" not found");
        }
    }

    private void testTransactionErrorReporting(AbstractServletContainerIntegrationTest test) throws Exception {
        for (String pathToTest : test.getPathsToTestErrors()) {
            String fullPathToTest = "/simple-webapp" + pathToTest;
            test.clearMockServerLog();
            // JBoss EAP 6.4 returns a 200 in case of an error in async dispatch 🤷‍♂️
            test.executeAndValidateRequest(fullPathToTest + "?cause_transaction_error=true", "", null, null);
            JsonNode transaction = test.assertTransactionReported(fullPathToTest, 500);
            String transactionId = transaction.get("id").textValue();
            test.assertSpansTransactionId(test::getReportedSpans, transactionId);
            // we currently only report errors when Exceptions are caught, still this test is relevant for response code capturing
            if (test.isExpectedStacktrace(pathToTest)) {
                test.assertErrorContent(500, test::getReportedErrors, transactionId, "Transaction failure");
            }
        }
    }

    /**
     * Tests that the capture_jmx_metrics config option works with each app server
     * Especially WildFly is problematic see {@link co.elastic.apm.agent.jmx.ManagementFactoryInstrumentation}
     */
    private void testJmxMetrics(AbstractServletContainerIntegrationTest test) throws Exception {
        // metrics_interval is 1s
        await()
            .pollDelay(Duration.ofMillis(500))
            .until(() -> {
                boolean hasMetricsets = !test.getEvents("metricset").isEmpty();
                if (!hasMetricsets) {
                    flush(test);
                }
                return hasMetricsets;
            });
        List<JsonNode> metricEvent = test.getEvents("metricset");
        assertThat(metricEvent.stream()
            .flatMap(metricset -> Streams.stream(metricset.get("samples").fieldNames()))
            .distinct())
            .contains("jvm.jmx.test_heap_metric.max");
    }

    private void flush(AbstractServletContainerIntegrationTest test) throws IOException {
        test.executeRequest("/simple-webapp/index.jsp", Collections.emptyMap());
    }
}
