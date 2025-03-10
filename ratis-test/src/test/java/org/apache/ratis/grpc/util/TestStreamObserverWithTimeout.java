/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.grpc.util;

import org.apache.ratis.BaseTest;
import org.apache.ratis.grpc.util.GrpcTestClient.StreamObserverFactory;
import io.grpc.StatusRuntimeException;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.Slf4jUtils;
import org.apache.ratis.util.StringUtils;
import org.apache.ratis.util.TimeDuration;
import org.apache.ratis.util.TimeoutTimer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class TestStreamObserverWithTimeout extends BaseTest {
  {
    Slf4jUtils.setLogLevel(ResponseNotifyClientInterceptor.LOG, Level.TRACE);
    Slf4jUtils.setLogLevel(StreamObserverWithTimeout.LOG, Level.DEBUG);
    Slf4jUtils.setLogLevel(TimeoutTimer.LOG, Level.DEBUG);
  }

  enum Type {
    WithDeadline(GrpcTestClient::withDeadline),
    WithTimeout(GrpcTestClient::withTimeout);

    private final Function<TimeDuration, StreamObserverFactory> factory;

    Type(Function<TimeDuration, StreamObserverFactory> function) {
      this.factory = function;
    }

    StreamObserverFactory createFunction(TimeDuration timeout) {
      return factory.apply(timeout);
    }
  }

  @Test
  public void testWithDeadline() throws Exception {
    //the total sleep time is within the deadline
    runTestTimeout(2, Type.WithDeadline);
  }

  @Test
  public void testWithDeadlineFailure() {
    //Expected to have DEADLINE_EXCEEDED
    testFailureCase("total sleep time is longer than the deadline",
        () -> runTestTimeout(5, Type.WithDeadline),
        ExecutionException.class, StatusRuntimeException.class);
  }

  @Test
  public void testWithTimeout() throws Exception {
    //Each sleep time is within the timeout,
    //Note that the total sleep time is longer than the timeout, but it does not matter.
    runTestTimeout(5, Type.WithTimeout);
  }

  void runTestTimeout(int slow, Type type) throws Exception {
    LOG.info("slow = {}, {}", slow, type);
    final TimeDuration timeout = ONE_SECOND.multiply(0.5);
    final StreamObserverFactory function = type.createFunction(timeout);

    // first request may take longer due to initialization
    final int warmup = type == Type.WithTimeout ? 1 : 0;
    final List<String> messages = new ArrayList<>();
    for (int i = 0; i < 2 * slow; i++) {
      messages.add("m" + (i + warmup));
    }
    try (GrpcTestServer server = new GrpcTestServer(NetUtils.getFreePort(), warmup, slow, timeout)) {
      final int port = server.start();
      try (GrpcTestClient client = new GrpcTestClient(NetUtils.LOCALHOST, port, function)) {

        if (warmup == 1) {
          client.send("warmup").join();
        }

        final List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String m : messages) {
          futures.add(client.send(m));
        }

        int i = 0;
        for (; i < slow; i++) {
          final String expected = (i + warmup) + GrpcTestServer.GreeterImpl.toReplySuffix(messages.get(i));
          final String reply = futures.get(i).get();
          Assertions.assertEquals(expected, reply);
          LOG.info("{}) passed", (i + warmup));
        }

        for (; i < messages.size(); i++) {
          final CompletableFuture<String> f = futures.get(i);
          try {
            final String reply = f.get();
            Assertions.fail((i + warmup) + ") reply = " + reply + ", "
                + StringUtils.completableFuture2String(f, false));
          } catch (ExecutionException e) {
             LOG.info("GOOD! {}) {}", (i + warmup), StringUtils.completableFuture2String(f, true), e);
          }
        }
      }
    }
  }
}
