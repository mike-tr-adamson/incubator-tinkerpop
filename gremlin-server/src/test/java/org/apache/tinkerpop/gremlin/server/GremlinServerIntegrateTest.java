/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.server;

import java.io.File;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.driver.ResultSet;
import org.apache.tinkerpop.gremlin.driver.Tokens;
import org.apache.tinkerpop.gremlin.driver.message.RequestMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.driver.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.driver.ser.Serializers;
import org.apache.tinkerpop.gremlin.driver.simple.NioClient;
import org.apache.tinkerpop.gremlin.driver.simple.SimpleClient;
import org.apache.tinkerpop.gremlin.driver.simple.WebSocketClient;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.CompileStaticCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.InterpreterModeCustomizerProvider;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.SimpleSandboxExtension;
import org.apache.tinkerpop.gremlin.groovy.jsr223.customizer.TimedInterruptCustomizerProvider;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.server.channel.NioChannelizer;
import org.apache.tinkerpop.gremlin.server.op.session.SessionOpProcessor;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.apache.tinkerpop.gremlin.util.Log4jRecordingAppender;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.StringEndsWith.endsWith;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeThat;

/**
 * Integration tests for server-side settings and processing.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class GremlinServerIntegrateTest extends AbstractGremlinServerIntegrationTest {

    private Log4jRecordingAppender recordingAppender = null;

    @Before
    public void setupForEachTest() {
        recordingAppender = new Log4jRecordingAppender();
        final Logger rootLogger = Logger.getRootLogger();
        rootLogger.addAppender(recordingAppender);
    }

    @After
    public void teardownForEachTest() {
        final Logger rootLogger = Logger.getRootLogger();
        rootLogger.removeAppender(recordingAppender);
    }

    /**
     * Configure specific Gremlin Server settings for specific tests.
     */
    @Override
    public Settings overrideSettings(final Settings settings) {
        final String nameOfTest = name.getMethodName();
        switch (nameOfTest) {
            case "shouldRespectHighWaterMarkSettingAndSucceed":
                settings.writeBufferHighWaterMark = 64;
                settings.writeBufferLowWaterMark = 32;
                break;
            case "shouldReceiveFailureTimeOutOnScriptEval":
                settings.scriptEvaluationTimeout = 200;
                break;
            case "shouldReceiveFailureTimeOutOnTotalSerialization":
                settings.serializedResponseTimeout = 1;
                break;
            case "shouldBlockRequestWhenTooBig":
                settings.maxContentLength = 1024;
                break;
            case "shouldBatchResultsByTwos":
                settings.resultIterationBatchSize = 2;
                break;
            case "shouldWorkOverNioTransport":
                settings.channelizer = NioChannelizer.class.getName();
                break;
            case "shouldEnableSsl":
            case "shouldEnableSslButFailIfClientConnectsWithoutIt":
                settings.ssl = new Settings.SslSettings();
                settings.ssl.enabled = true;
                break;
            case "shouldStartWithDefaultSettings":
                return new Settings();
            case "shouldHaveTheSessionTimeout":
                settings.processors.clear();
                final Settings.ProcessorSettings processorSettings = new Settings.ProcessorSettings();
                processorSettings.className = SessionOpProcessor.class.getCanonicalName();
                processorSettings.config = new HashMap<>();
                processorSettings.config.put(SessionOpProcessor.CONFIG_SESSION_TIMEOUT, 3000L);
                settings.processors.add(processorSettings);
                break;
            case "shouldExecuteInSessionAndSessionlessWithoutOpeningTransactionWithSingleClient":
            case "shouldExecuteInSessionWithTransactionManagement":
                deleteDirectory(new File("/tmp/neo4j"));
                settings.graphs.put("graph", "conf/neo4j-empty.properties");
                break;
            case "shouldUseSimpleSandbox":
                settings.scriptEngines.get("gremlin-groovy").config = getScriptEngineConfForSimpleSandbox();
                break;
            case "shouldUseInterpreterMode":
                settings.scriptEngines.get("gremlin-groovy").config = getScriptEngineConfForInterpreterMode();
                break;
            case "shouldReceiveFailureTimeOutOnScriptEvalOfOutOfControlLoop":
                settings.scriptEngines.get("gremlin-groovy").config = getScriptEngineConfForTimedInterrupt();
                break;
        }

        return settings;
    }

    private static Map<String, Object> getScriptEngineConfForSimpleSandbox() {
        final Map<String,Object> scriptEngineConf = new HashMap<>();
        final Map<String,Object> compilerCustomizerProviderConf = new HashMap<>();
        final List<String> sandboxes = new ArrayList<>();
        sandboxes.add(SimpleSandboxExtension.class.getName());
        compilerCustomizerProviderConf.put(CompileStaticCustomizerProvider.class.getName(), sandboxes);
        scriptEngineConf.put("compilerCustomizerProviders", compilerCustomizerProviderConf);
        return scriptEngineConf;
    }

    private static Map<String, Object> getScriptEngineConfForTimedInterrupt() {
        final Map<String,Object> scriptEngineConf = new HashMap<>();
        final Map<String,Object> timedInterruptProviderConf = new HashMap<>();
        final List<Object> config = new ArrayList<>();
        config.add(1000);
        timedInterruptProviderConf.put(TimedInterruptCustomizerProvider.class.getName(), config);
        scriptEngineConf.put("compilerCustomizerProviders", timedInterruptProviderConf);
        return scriptEngineConf;
    }

    private static Map<String, Object> getScriptEngineConfForInterpreterMode() {
        final Map<String,Object> scriptEngineConf = new HashMap<>();
        final Map<String,Object> interpreterProviderConf = new HashMap<>();
        interpreterProviderConf.put(InterpreterModeCustomizerProvider.class.getName(), Collections.EMPTY_LIST);
        scriptEngineConf.put("compilerCustomizerProviders", interpreterProviderConf);
        return scriptEngineConf;
    }

    @Test
    public void shouldUseInterpreterMode() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect(name.getMethodName());

        client.submit("def subtractAway(x,y){x-y};[]").all().get();
        client.submit("multiplyIt = { x,y -> x * y};[]").all().get();

        assertEquals(2, client.submit("x = 1 + 1").all().get().get(0).getInt());
        assertEquals(3, client.submit("int y = x + 1").all().get().get(0).getInt());
        assertEquals(5, client.submit("def z = x + y").all().get().get(0).getInt());

        final Map<String,Object> m = new HashMap<>();
        m.put("x", 10);
        assertEquals(-5, client.submit("z - x", m).all().get().get(0).getInt());
        assertEquals(15, client.submit("addItUp(x,z)", m).all().get().get(0).getInt());
        assertEquals(5, client.submit("subtractAway(x,z)", m).all().get().get(0).getInt());
        assertEquals(50, client.submit("multiplyIt(x,z)", m).all().get().get(0).getInt());

        cluster.close();
    }

    @Test
    public void shouldNotUseInterpreterMode() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect(name.getMethodName());

        client.submit("def subtractAway(x,y){x-y};[]").all().get();
        client.submit("multiplyIt = { x,y -> x * y};[]").all().get();

        assertEquals(2, client.submit("x = 1 + 1").all().get().get(0).getInt());
        assertEquals(3, client.submit("y = x + 1").all().get().get(0).getInt());
        assertEquals(5, client.submit("z = x + y").all().get().get(0).getInt());

        final Map<String,Object> m = new HashMap<>();
        m.put("x", 10);
        assertEquals(-5, client.submit("z - x", m).all().get().get(0).getInt());
        assertEquals(15, client.submit("addItUp(x,z)", m).all().get().get(0).getInt());
        assertEquals(5, client.submit("subtractAway(x,z)", m).all().get().get(0).getInt());
        assertEquals(50, client.submit("multiplyIt(x,z)", m).all().get().get(0).getInt());

        cluster.close();
    }

    @Test
    public void shouldUseSimpleSandbox() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        assertEquals(2, client.submit("1+1").all().get().get(0).getInt());

        try {
            // this should return "nothing" - there should be no exception
            client.submit("java.lang.System.exit(0)").all().get();
            fail("The above should not have executed in any successful way as sandboxing is enabled");
        } catch (Exception ex) {
            assertThat(ex.getCause().getMessage(), containsString("[Static type checking] - Not authorized to call this method: java.lang.System#exit(int)"));
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldStartWithDefaultSettings() {
        // just quickly validate that results are returning given defaults. no graphs are config'd with defaults
        // so just eval a groovy script.
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        final ResultSet results = client.submit("[1,2,3,4,5,6,7,8,9]");
        final AtomicInteger counter = new AtomicInteger(0);
        results.stream().map(i -> i.get(Integer.class) * 2).forEach(i -> assertEquals(counter.incrementAndGet() * 2, Integer.parseInt(i.toString())));

        cluster.close();
    }

    @Test
    public void shouldEnableSsl() {
        final Cluster cluster = Cluster.build().enableSsl(true).create();
        final Client client = cluster.connect();

        try {
            // this should return "nothing" - there should be no exception
            assertEquals("test", client.submit("'test'").one().getString());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldEnableSslButFailIfClientConnectsWithoutIt() {
        final Cluster cluster = Cluster.build().enableSsl(false).create();
        final Client client = cluster.connect();

        try {
            client.submit("'test'").one();
            fail("Should throw exception because ssl is enabled on the server but not on client");
        } catch(Exception x) {
            final Throwable root = ExceptionUtils.getRootCause(x);
            assertThat(root, instanceOf(TimeoutException.class));
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldRespectHighWaterMarkSettingAndSucceed() throws Exception {
        // the highwatermark should get exceeded on the server and thus pause the writes, but have no problem catching
        // itself up - this is a tricky tests to get passing on all environments so this assumption will deny the
        // test for most cases
        assumeThat("Set the 'assertNonDeterministic' property to true to execute this test",
                System.getProperty("assertNonDeterministic"), is("true"));

        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        try {
            final int resultCountToGenerate = 1000;
            final int batchSize = 3;
            final String fatty = IntStream.range(0, 175).mapToObj(String::valueOf).collect(Collectors.joining());
            final String fattyX = "['" + fatty + "'] * " + resultCountToGenerate;

            // don't allow the thread to proceed until all results are accounted for
            final CountDownLatch latch = new CountDownLatch(resultCountToGenerate);
            final AtomicBoolean expected = new AtomicBoolean(false);
            final AtomicBoolean faulty = new AtomicBoolean(false);
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_BATCH_SIZE, batchSize)
                    .addArg(Tokens.ARGS_GREMLIN, fattyX).create();

            client.submitAsync(request).thenAcceptAsync(r -> {
                r.stream().forEach(item -> {
                    try {
                        final String aFattyResult = item.getString();
                        expected.set(aFattyResult.equals(fatty));
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        faulty.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            });

            assertTrue(latch.await(30000, TimeUnit.MILLISECONDS));
            assertEquals(0, latch.getCount());
            assertFalse(faulty.get());
            assertTrue(expected.get());

            assertTrue(recordingAppender.getMessages().stream().anyMatch(m -> m.contains("Pausing response writing as writeBufferHighWaterMark exceeded on")));
        } catch (Exception ex) {
            fail("Shouldn't have tossed an exception");
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldReturnInvalidRequestArgsWhenGremlinArgIsNotSupplied() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL).create();
            final ResponseMessage result = client.submit(request).get(0);
            assertThat(result.getStatus().getCode(), is(not(ResponseStatusCode.PARTIAL_CONTENT)));
            assertEquals(result.getStatus().getCode(), ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS);
        }
    }

    @Test
    public void shouldReturnInvalidRequestArgsWhenInvalidReservedBindingKeyIsUsed() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final Map<String, Object> bindings = new HashMap<>();
            bindings.put(T.id.getAccessor(), "123");
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "[1,2,3,4,5,6,7,8,9,0]")
                    .addArg(Tokens.ARGS_BINDINGS, bindings).create();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean pass = new AtomicBoolean(false);
            client.submit(request, result -> {
                if (result.getStatus().getCode() != ResponseStatusCode.PARTIAL_CONTENT) {
                    pass.set(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS == result.getStatus().getCode());
                    latch.countDown();
                }
            });

            if (!latch.await(3000, TimeUnit.MILLISECONDS))
                fail("Request should have returned error, but instead timed out");
            assertTrue(pass.get());
        }
    }

    @Test
    public void shouldReturnInvalidRequestArgsWhenInvalidTypeBindingKeyIsUsed() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final Map<Object, Object> bindings = new HashMap<>();
            bindings.put(1, "123");
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "[1,2,3,4,5,6,7,8,9,0]")
                    .addArg(Tokens.ARGS_BINDINGS, bindings).create();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean pass = new AtomicBoolean(false);
            client.submit(request, result -> {
                if (result.getStatus().getCode() != ResponseStatusCode.PARTIAL_CONTENT) {
                    pass.set(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS == result.getStatus().getCode());
                    latch.countDown();
                }
            });

            if (!latch.await(3000, TimeUnit.MILLISECONDS))
                fail("Request should have returned error, but instead timed out");
            assertTrue(pass.get());
        }
    }

    @Test
    public void shouldReturnInvalidRequestArgsWhenInvalidNullBindingKeyIsUsed() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final Map<String, Object> bindings = new HashMap<>();
            bindings.put(null, "123");
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "[1,2,3,4,5,6,7,8,9,0]")
                    .addArg(Tokens.ARGS_BINDINGS, bindings).create();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean pass = new AtomicBoolean(false);
            client.submit(request, result -> {
                if (result.getStatus().getCode() != ResponseStatusCode.PARTIAL_CONTENT) {
                    pass.set(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS == result.getStatus().getCode());
                    latch.countDown();
                }
            });

            if (!latch.await(3000, TimeUnit.MILLISECONDS))
                fail("Request should have returned error, but instead timed out");
            assertTrue(pass.get());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBatchResultsByTwos() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "[0,1,2,3,4,5,6,7,8,9]").create();

            final List<ResponseMessage> msgs = client.submit(request);
            assertEquals(5, client.submit(request).size());
            assertEquals(0, ((List<Integer>) msgs.get(0).getResult().getData()).get(0).intValue());
            assertEquals(1, ((List<Integer>) msgs.get(0).getResult().getData()).get(1).intValue());
            assertEquals(2, ((List<Integer>) msgs.get(1).getResult().getData()).get(0).intValue());
            assertEquals(3, ((List<Integer>) msgs.get(1).getResult().getData()).get(1).intValue());
            assertEquals(4, ((List<Integer>) msgs.get(2).getResult().getData()).get(0).intValue());
            assertEquals(5, ((List<Integer>) msgs.get(2).getResult().getData()).get(1).intValue());
            assertEquals(6, ((List<Integer>) msgs.get(3).getResult().getData()).get(0).intValue());
            assertEquals(7, ((List<Integer>) msgs.get(3).getResult().getData()).get(1).intValue());
            assertEquals(8, ((List<Integer>) msgs.get(4).getResult().getData()).get(0).intValue());
            assertEquals(9, ((List<Integer>) msgs.get(4).getResult().getData()).get(1).intValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBatchResultsByOnesByOverridingFromClientSide() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "[0,1,2,3,4,5,6,7,8,9]")
                    .addArg(Tokens.ARGS_BATCH_SIZE, 1).create();

            final List<ResponseMessage> msgs = client.submit(request);
            assertEquals(10, msgs.size());
            IntStream.rangeClosed(0, 9).forEach(i -> assertEquals(i, ((List<Integer>) msgs.get(i).getResult().getData()).get(0).intValue()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldWorkOverNioTransport() throws Exception {
        try (SimpleClient client = new NioClient()) {
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "[0,1,2,3,4,5,6,7,8,9,]").create();

            final List<ResponseMessage> msg = client.submit(request);
            assertEquals(1, msg.size());
            final List<Integer> integers = (List<Integer>) msg.get(0).getResult().getData();
            IntStream.rangeClosed(0, 9).forEach(i -> assertEquals(i, integers.get(i).intValue()));
        }
    }

    @Test
    public void shouldNotThrowNoSuchElementException() throws Exception {
        try (SimpleClient client = new WebSocketClient()){
            // this should return "nothing" - there should be no exception
            final List<ResponseMessage> responses = client.submit("g.V().has('name','kadfjaldjfla')");
            assertNull(responses.get(0).getResult().getData());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReceiveFailureTimeOutOnScriptEval() throws Exception {
        try (SimpleClient client = new WebSocketClient()){
            final List<ResponseMessage> responses = client.submit("Thread.sleep(3000);'some-stuff-that-should not return'");
            assertThat(responses.get(0).getStatus().getMessage(), startsWith("Script evaluation exceeded the configured 'scriptEvaluationTimeout' threshold of 200 ms for request"));

            // validate that we can still send messages to the server
            assertEquals(2, ((List<Integer>) client.submit("1+1").get(0).getResult().getData()).get(0).intValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReceiveFailureTimeOutOnScriptEvalUsingOverride() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final RequestMessage msg = RequestMessage.build("eval")
                    .addArg(Tokens.ARGS_SCRIPT_EVAL_TIMEOUT, 100)
                    .addArg(Tokens.ARGS_GREMLIN, "Thread.sleep(3000);'some-stuff-that-should not return'")
                    .create();
            final List<ResponseMessage> responses = client.submit(msg);
            assertThat(responses.get(0).getStatus().getMessage(), startsWith("Script evaluation exceeded the configured 'scriptEvaluationTimeout' threshold of 100 ms for request"));

            // validate that we can still send messages to the server
            assertEquals(2, ((List<Integer>) client.submit("1+1").get(0).getResult().getData()).get(0).intValue());
        }
    }

    @Test
    public void shouldReceiveFailureTimeOutOnScriptEvalOfOutOfControlLoop() throws Exception {
        try (SimpleClient client = new WebSocketClient()){
            // timeout configured for 1 second so the timed interrupt should trigger prior to the
            // scriptEvaluationTimeout which is at 30 seconds by default
            final List<ResponseMessage> responses = client.submit("while(true){}");
            assertThat(responses.get(0).getStatus().getMessage(), startsWith("Timeout during script evaluation triggered by TimedInterruptCustomizerProvider"));

            // validate that we can still send messages to the server
            assertEquals(2, ((List<Integer>) client.submit("1+1").get(0).getResult().getData()).get(0).intValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReceiveFailureTimeOutOnTotalSerialization() throws Exception {
        try (SimpleClient client = new WebSocketClient()){
            final List<ResponseMessage> responses = client.submit("(0..<100000)");

            // the last message should contain the error
            assertThat(responses.get(responses.size() - 1).getStatus().getMessage(), endsWith("Serialization of the entire response exceeded the 'serializeResponseTimeout' setting"));

            // validate that we can still send messages to the server
            assertEquals(2, ((List<Integer>) client.submit("1+1").get(0).getResult().getData()).get(0).intValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldLoadInitScript() throws Exception {
        try (SimpleClient client = new WebSocketClient()){
            assertEquals(2, ((List<Integer>) client.submit("addItUp(1,1)").get(0).getResult().getData()).get(0).intValue());
        }
    }

    @Test
    public void shouldGarbageCollectPhantomButNotHard() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        assertEquals(2, client.submit("addItUp(1,1)").all().join().get(0).getInt());
        assertEquals(0, client.submit("def subtract(x,y){x-y};subtract(1,1)").all().join().get(0).getInt());
        assertEquals(0, client.submit("subtract(1,1)").all().join().get(0).getInt());

        final Map<String, Object> bindings = new HashMap<>();
        bindings.put(GremlinGroovyScriptEngine.KEY_REFERENCE_TYPE, GremlinGroovyScriptEngine.REFERENCE_TYPE_PHANTOM);
        assertEquals(4, client.submit("def multiply(x,y){x*y};multiply(2,2)", bindings).all().join().get(0).getInt());

        try {
            client.submit("multiply(2,2)").all().join().get(0).getInt();
            fail("Should throw an exception since reference is phantom.");
        } catch (RuntimeException ignored) {

        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldReceiveFailureOnBadGraphSONSerialization() throws Exception {
        final Cluster cluster = Cluster.build("localhost").serializer(Serializers.GRAPHSON_V1D0).create();
        final Client client = cluster.connect();

        try {
            client.submit("def class C { def C getC(){return this}}; new C()").all().join();
            fail("Should throw an exception.");
        } catch (RuntimeException re) {
            final Throwable root = ExceptionUtils.getRootCause(re);
            assertThat(root.getMessage(), CoreMatchers.startsWith("Error during serialization: Direct self-reference leading to cycle (through reference chain:"));

            // validate that we can still send messages to the server
            assertEquals(2, client.submit("1+1").all().join().get(0).getInt());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldReceiveFailureOnBadGryoSerialization() throws Exception {
        final Cluster cluster = Cluster.build("localhost").serializer(Serializers.GRYO_V1D0).create();
        final Client client = cluster.connect();

        try {
            client.submit("java.awt.Color.RED").all().join();
            fail("Should throw an exception.");
        } catch (RuntimeException re) {
            final Throwable root = ExceptionUtils.getRootCause(re);
            assertThat(root.getMessage(), CoreMatchers.startsWith("Error during serialization: Class is not registered: java.awt.Color"));

            // validate that we can still send messages to the server
            assertEquals(2, client.submit("1+1").all().join().get(0).getInt());
        } finally {
            cluster.close();
        }
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    @Test
    public void shouldBlockRequestWhenTooBig() throws Exception {
        final Cluster cluster = Cluster.open();
        final Client client = cluster.connect();

        try {
            final String fatty = IntStream.range(0, 1024).mapToObj(String::valueOf).collect(Collectors.joining());
            final CompletableFuture<ResultSet> result = client.submitAsync("'" + fatty + "';'test'");
            final ResultSet resultSet = result.get(10000, TimeUnit.MILLISECONDS);
            resultSet.all().get(10000, TimeUnit.MILLISECONDS);
            fail("Should throw an exception.");
        } catch (TimeoutException te) {
            // the request should not have timed-out - the connection should have been reset, but it seems that
            // timeout seems to occur as well on some systems (it's not clear why).  however, the nature of this
            // test is to ensure that the script isn't processed if it exceeds a certain size, so in this sense
            // it seems ok to pass in this case.
        } catch (Exception re) {
            final Throwable root = ExceptionUtils.getRootCause(re);
            assertEquals("Connection reset by peer", root.getMessage());

            // validate that we can still send messages to the server
            assertEquals(2, client.submit("1+1").all().join().get(0).getInt());
        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldFailOnDeadHost() throws Exception {
        final Cluster cluster = Cluster.build("localhost").create();
        final Client client = cluster.connect();

        // ensure that connection to server is good
        assertEquals(2, client.submit("1+1").all().join().get(0).getInt());

        // kill the server which will make the client mark the host as unavailable
        this.stopServer();

        try {
            // try to re-issue a request now that the server is down
            client.submit("1+1").all().join();
            fail();
        } catch (RuntimeException re) {
            assertTrue(re.getCause().getCause() instanceof ClosedChannelException);

            //
            // should recover when the server comes back
            //

            // restart server
            this.startServer();
            // the retry interval is 1 second, wait a bit longer
            TimeUnit.SECONDS.sleep(5);

            List<Result> results = client.submit("1+1").all().join();
            assertEquals(1, results.size());
            assertEquals(2, results.get(0).getInt());

        } finally {
            cluster.close();
        }
    }

    @Test
    public void shouldNotHavePartialContentWithOneResult() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "10").create();
            final List<ResponseMessage> responses = client.submit(request);
            assertEquals(1, responses.size());
            assertEquals(ResponseStatusCode.SUCCESS, responses.get(0).getStatus().getCode());
        }
    }

    @Test
    public void shouldFailWithBadScriptEval() throws Exception {
        try (SimpleClient client = new WebSocketClient()) {
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "new String().doNothingAtAllBecauseThis is a syntax error").create();
            final List<ResponseMessage> responses = client.submit(request);
            assertEquals(ResponseStatusCode.SERVER_ERROR_SCRIPT_EVALUATION, responses.get(0).getStatus().getCode());
            assertEquals(1, responses.size());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldStillSupportDeprecatedRebindingsParameterOnServer() throws Exception {
        // this test can be removed when the rebindings arg is removed
        try (SimpleClient client = new WebSocketClient()) {
            final Map<String,String> rebindings = new HashMap<>();
            rebindings.put("xyz", "graph");
            final RequestMessage request = RequestMessage.build(Tokens.OPS_EVAL)
                    .addArg(Tokens.ARGS_GREMLIN, "xyz.addVertex('name','jason')")
                    .addArg(Tokens.ARGS_REBINDINGS, rebindings).create();
            final List<ResponseMessage> responses = client.submit(request);
            assertEquals(1, responses.size());

            final DetachedVertex v = ((ArrayList<DetachedVertex>) responses.get(0).getResult().getData()).get(0);
            assertEquals("jason", v.value("name"));
        }
    }
}
