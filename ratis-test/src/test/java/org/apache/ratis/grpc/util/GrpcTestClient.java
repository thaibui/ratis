/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.grpc.util;

import org.apache.ratis.test.proto.GreeterGrpc;
import org.apache.ratis.test.proto.GreeterGrpc.GreeterStub;
import org.apache.ratis.test.proto.HelloReply;
import org.apache.ratis.test.proto.HelloRequest;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.ratis.util.IOUtils;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/** gRPC client for testing */
class GrpcTestClient implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(GrpcTestClient.class);

  @FunctionalInterface
  interface StreamObserverFactory
      extends BiFunction<GreeterStub, StreamObserver<HelloReply>, StreamObserver<HelloRequest>> {
  }

  static StreamObserverFactory withDeadline(TimeDuration timeout) {
    final Deadline d = Deadline.after(timeout.getDuration(), timeout.getUnit());
    return (stub, responseHandler) -> stub.withDeadline(d).hello(responseHandler);
  }

  static StreamObserverFactory withTimeout(TimeDuration timeout) {
    final String className = JavaUtils.getClassSimpleName(HelloRequest.class) + ":";
    final AtomicBoolean initialized = new AtomicBoolean();
    return (stub, responseHandler) -> StreamObserverWithTimeout.newInstance("test",
        r -> className + r.getName(),
        () -> initialized.getAndSet(true) ? timeout : TimeDuration.ONE_MINUTE.add(timeout),
        2,
        i -> stub.withInterceptors(i).hello(responseHandler));
  }

  private final ManagedChannel channel;
  private final StreamObserver<HelloRequest> requestHandler;
  private final Queue<CompletableFuture<String>> replies = new ConcurrentLinkedQueue<>();

  GrpcTestClient(String host, int port, StreamObserverFactory factory) {
    this.channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build();

    final GreeterStub asyncStub = GreeterGrpc.newStub(channel);
    final StreamObserver<HelloReply> responseHandler = new StreamObserver<HelloReply>() {
      @Override
      public void onNext(HelloReply helloReply) {
        replies.poll().complete(helloReply.getMessage());
      }

      @Override
      public void onError(Throwable throwable) {
        LOG.info("onError", throwable);
        completeExceptionally(throwable);
      }

      @Override
      public void onCompleted() {
        LOG.info("onCompleted");
        completeExceptionally(new IllegalStateException("onCompleted"));
      }

      void completeExceptionally(Throwable throwable) {
        replies.forEach(f -> f.completeExceptionally(throwable));
        replies.clear();
      }
    };

    this.requestHandler = factory.apply(asyncStub, responseHandler);
  }

  @Override
  public void close() throws IOException {
    try {
      /* After the request handler is cancelled, no more life-cycle hooks are allowed,
       * see {@link io.grpc.ClientCall.Listener#cancel(String, Throwable)} */
      // requestHandler.onCompleted();
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw IOUtils.toInterruptedIOException("Failed to close", e);
    }
  }

  CompletableFuture<String> send(String name) {
    LOG.info("send {}", name);
    final HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    final CompletableFuture<String> f = new CompletableFuture<>();
    try {
      requestHandler.onNext(request);
      replies.offer(f);
    } catch (IllegalStateException e) {
      // already closed
      f.completeExceptionally(e);
    }
    return f;
  }
}