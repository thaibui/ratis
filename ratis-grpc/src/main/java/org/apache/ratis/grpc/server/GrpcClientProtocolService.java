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
package org.apache.ratis.grpc.server;

import org.apache.ratis.client.impl.ClientProtoUtils;
import org.apache.ratis.grpc.GrpcUtil;
import org.apache.ratis.grpc.metrics.ZeroCopyMetrics;
import org.apache.ratis.grpc.util.ZeroCopyMessageMarshaller;
import org.apache.ratis.protocol.*;
import org.apache.ratis.protocol.exceptions.AlreadyClosedException;
import org.apache.ratis.protocol.exceptions.GroupMismatchException;
import org.apache.ratis.protocol.exceptions.RaftException;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;
import org.apache.ratis.proto.RaftProtos.RaftClientReplyProto;
import org.apache.ratis.proto.RaftProtos.RaftClientRequestProto;
import org.apache.ratis.proto.RaftProtos.SlidingWindowEntry;
import org.apache.ratis.proto.grpc.RaftClientProtocolServiceGrpc.RaftClientProtocolServiceImplBase;
import org.apache.ratis.util.CollectionUtils;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.Preconditions;
import org.apache.ratis.util.ReferenceCountedObject;
import org.apache.ratis.util.SlidingWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.apache.ratis.grpc.GrpcUtil.addMethodWithCustomMarshaller;
import static org.apache.ratis.proto.grpc.RaftClientProtocolServiceGrpc.getOrderedMethod;
import static org.apache.ratis.proto.grpc.RaftClientProtocolServiceGrpc.getUnorderedMethod;

class GrpcClientProtocolService extends RaftClientProtocolServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(GrpcClientProtocolService.class);

  private static class PendingOrderedRequest implements SlidingWindow.ServerSideRequest<RaftClientReply> {
    private final ReferenceCountedObject<RaftClientRequest> requestRef;
    private final RaftClientRequest request;
    private final AtomicReference<RaftClientReply> reply = new AtomicReference<>();

    PendingOrderedRequest(ReferenceCountedObject<RaftClientRequest> requestRef) {
      this.requestRef = requestRef;
      this.request = requestRef != null ? requestRef.retain() : null;
    }

    @Override
    public void fail(Throwable t) {
      final RaftException e = Preconditions.assertInstanceOf(t, RaftException.class);
      setReply(RaftClientReply.newBuilder()
          .setRequest(request)
          .setException(e)
          .build());
    }

    @Override
    public boolean hasReply() {
      return getReply() != null || this == COMPLETED;
    }

    @Override
    public void setReply(RaftClientReply r) {
      final boolean set = reply.compareAndSet(null, r);
      Preconditions.assertTrue(set, () -> "Reply is already set: request=" +
          request.toStringShort() + ", reply=" + reply);
    }

    RaftClientReply getReply() {
      return reply.get();
    }

    ReferenceCountedObject<RaftClientRequest> getRequestRef() {
      return requestRef;
    }

    @Override
    public void release() {
      if (requestRef != null) {
        requestRef.release();
      }
    }

    @Override
    public long getSeqNum() {
      return request != null? request.getSlidingWindowEntry().getSeqNum(): Long.MAX_VALUE;
    }

    @Override
    public boolean isFirstRequest() {
      return request != null && request.getSlidingWindowEntry().getIsFirst();
    }

    @Override
    public String toString() {
      return request != null? getSeqNum() + ":" + reply: "COMPLETED";
    }
  }
  private static final PendingOrderedRequest COMPLETED = new PendingOrderedRequest(null);

  static class OrderedStreamObservers {
    private final Map<Integer, OrderedRequestStreamObserver> map = new ConcurrentHashMap<>();

    void putNew(OrderedRequestStreamObserver so) {
      CollectionUtils.putNew(so.getId(), so, map, () -> JavaUtils.getClassSimpleName(getClass()));
    }

    void removeExisting(OrderedRequestStreamObserver so) {
      CollectionUtils.removeExisting(so.getId(), so, map, () -> JavaUtils.getClassSimpleName(getClass()));
    }

    void closeAllExisting(RaftGroupId groupId) {
      // Iteration not synchronized:
      // Okay if an existing object is removed by another mean during the iteration since it must be already closed.
      // Also okay if a new object is added during the iteration since this method closes only the existing objects.
      for(Iterator<Map.Entry<Integer, OrderedRequestStreamObserver>> i = map.entrySet().iterator(); i.hasNext(); ) {
        final OrderedRequestStreamObserver so = i.next().getValue();
        final RaftGroupId gid = so.getGroupId();
        if (gid == null || gid.equals(groupId)) {
          so.close(true);
          i.remove();
        }
      }
    }
  }

  private final Supplier<RaftPeerId> idSupplier;
  private final RaftClientAsynchronousProtocol protocol;
  private final ExecutorService executor;

  private final OrderedStreamObservers orderedStreamObservers = new OrderedStreamObservers();
  private final ZeroCopyMessageMarshaller<RaftClientRequestProto> zeroCopyRequestMarshaller;

  GrpcClientProtocolService(Supplier<RaftPeerId> idSupplier, RaftClientAsynchronousProtocol protocol,
      ExecutorService executor, ZeroCopyMetrics zeroCopyMetrics) {
    this.idSupplier = idSupplier;
    this.protocol = protocol;
    this.executor = executor;
    this.zeroCopyRequestMarshaller = new ZeroCopyMessageMarshaller<>(RaftClientRequestProto.getDefaultInstance(),
        zeroCopyMetrics::onZeroCopyMessage, zeroCopyMetrics::onNonZeroCopyMessage, zeroCopyMetrics::onReleasedMessage);
  }

  RaftPeerId getId() {
    return idSupplier.get();
  }

  ServerServiceDefinition bindServiceWithZeroCopy() {
    ServerServiceDefinition orig = super.bindService();
    ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(orig.getServiceDescriptor().getName());

    addMethodWithCustomMarshaller(orig, builder, getOrderedMethod(), zeroCopyRequestMarshaller);
    addMethodWithCustomMarshaller(orig, builder, getUnorderedMethod(), zeroCopyRequestMarshaller);

    return builder.build();
  }

  @Override
  public StreamObserver<RaftClientRequestProto> ordered(StreamObserver<RaftClientReplyProto> responseObserver) {
    final OrderedRequestStreamObserver so = new OrderedRequestStreamObserver(responseObserver);
    orderedStreamObservers.putNew(so);
    return so;
  }

  void closeAllOrderedRequestStreamObservers(RaftGroupId groupId) {
    LOG.debug("{}: closeAllOrderedRequestStreamObservers", getId());
    orderedStreamObservers.closeAllExisting(groupId);
  }

  @Override
  public StreamObserver<RaftClientRequestProto> unordered(StreamObserver<RaftClientReplyProto> responseObserver) {
    return new UnorderedRequestStreamObserver(responseObserver);
  }

  private final AtomicInteger streamCount = new AtomicInteger();

  private abstract class RequestStreamObserver implements StreamObserver<RaftClientRequestProto> {
    private final int id = streamCount.getAndIncrement();
    private final String name = getId() + "-" + JavaUtils.getClassSimpleName(getClass()) + id;
    private final StreamObserver<RaftClientReplyProto> responseObserver;
    private final AtomicBoolean isClosed = new AtomicBoolean();

    RequestStreamObserver(StreamObserver<RaftClientReplyProto> responseObserver) {
      LOG.debug("new {}", name);
      this.responseObserver = responseObserver;
    }

    int getId() {
      return id;
    }

    String getName() {
      return name;
    }

    synchronized void responseNext(RaftClientReplyProto reply) {
      responseObserver.onNext(reply);
    }

    synchronized void responseCompleted() {
      try {
        responseObserver.onCompleted();
      } catch(Exception e) {
        // response stream may possibly be already closed/failed so that the exception can be safely ignored.
        if (LOG.isTraceEnabled()) {
          LOG.trace(getName() + ": Failed onCompleted, exception is ignored", e);
        }
      }
    }

    synchronized void responseError(Throwable t) {
      try {
        responseObserver.onError(t);
      } catch(Exception e) {
        // response stream may possibly be already closed/failed so that the exception can be safely ignored.
        if (LOG.isTraceEnabled()) {
          LOG.trace(getName() + ": Failed onError, exception is ignored", e);
        }
      }
    }


    boolean setClose() {
      return isClosed.compareAndSet(false, true);
    }

    boolean isClosed() {
      return isClosed.get();
    }

    CompletableFuture<Void> processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef,
        Consumer<RaftClientReply> replyHandler) {
      final String errMsg = LOG.isDebugEnabled() ? "processClientRequest for " + requestRef.get() : "";
      return protocol.submitClientRequestAsync(requestRef
      ).thenAcceptAsync(replyHandler, executor
      ).exceptionally(exception -> {
        // TODO: the exception may be from either raft or state machine.
        // Currently we skip all the following responses when getting an
        // exception from the state machine.
        responseError(exception, () -> errMsg);
        return null;
      });
    }

    abstract void processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef);

    @Override
    public void onNext(RaftClientRequestProto request) {
      ReferenceCountedObject<RaftClientRequest> requestRef = null;
      try {
        final RaftClientRequest r = ClientProtoUtils.toRaftClientRequest(request);
        requestRef = ReferenceCountedObject.wrap(r, () -> {}, released -> {
          if (released) {
            zeroCopyRequestMarshaller.release(request);
          }
        });

        processClientRequest(requestRef);
      } catch (Exception e) {
        if (requestRef == null) {
          zeroCopyRequestMarshaller.release(request);
        }
        responseError(e, () -> "onNext for " + ClientProtoUtils.toString(request) + " in " + name);
      }
    }

    @Override
    public void onError(Throwable t) {
      // for now we just log a msg
      GrpcUtil.warn(LOG, () -> name + ": onError", t);
    }


    boolean responseError(Throwable t, Supplier<String> message) {
      if (setClose()) {
        t = JavaUtils.unwrapCompletionException(t);
        if (LOG.isDebugEnabled()) {
          LOG.debug(name + ": Failed " + message.get(), t);
        }
        responseError(GrpcUtil.wrapException(t));
        return true;
      }
      return false;
    }
  }

  private class UnorderedRequestStreamObserver extends RequestStreamObserver {
    /** Map: callId -> futures (seqNum is not set for unordered requests) */
    private final Map<Long, CompletableFuture<Void>> futures = new HashMap<>();

    UnorderedRequestStreamObserver(StreamObserver<RaftClientReplyProto> responseObserver) {
      super(responseObserver);
    }

    @Override
    void processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef) {
      final RaftClientRequest request = requestRef.retain();
      final long callId = request.getCallId();
      final SlidingWindowEntry slidingWindowEntry = request.getSlidingWindowEntry();
      final CompletableFuture<Void> f = processClientRequest(requestRef, reply -> {
        if (!reply.isSuccess()) {
          LOG.info("Failed request cid={}, {}, reply={}", callId, slidingWindowEntry, reply);
        }
        final RaftClientReplyProto proto = ClientProtoUtils.toRaftClientReplyProto(reply);
        responseNext(proto);
      });

      requestRef.release();

      put(callId, f);
      f.thenAccept(dummy -> remove(callId));
    }

    private synchronized void put(long callId, CompletableFuture<Void> f) {
      futures.put(callId, f);
    }
    private synchronized void remove(long callId) {
      futures.remove(callId);
    }

    private synchronized CompletableFuture<Void> allOfFutures() {
      return JavaUtils.allOf(futures.values());
    }

    @Override
    public void onCompleted() {
      allOfFutures().thenAccept(dummy -> {
        if (setClose()) {
          LOG.debug("{}: close", getName());
          responseCompleted();
        }
      });
    }
  }

  private class OrderedRequestStreamObserver extends RequestStreamObserver {
    private final SlidingWindow.Server<PendingOrderedRequest, RaftClientReply> slidingWindow
        = new SlidingWindow.Server<>(getName(), COMPLETED);
    /** The {@link RaftGroupId} for this observer. */
    private final AtomicReference<RaftGroupId> groupId = new AtomicReference<>();

    OrderedRequestStreamObserver(StreamObserver<RaftClientReplyProto> responseObserver) {
      super(responseObserver);
    }

    RaftGroupId getGroupId() {
      return groupId.get();
    }

    void processClientRequest(PendingOrderedRequest pending) {
      final long seq = pending.getSeqNum();
      processClientRequest(pending.getRequestRef(),
          reply -> slidingWindow.receiveReply(seq, reply, this::sendReply));
    }

    @Override
    void processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef) {
      final RaftClientRequest request = requestRef.retain();
      try {
        if (isClosed()) {
          final AlreadyClosedException exception = new AlreadyClosedException(getName() + ": the stream is closed");
          responseError(exception, () -> "processClientRequest (stream already closed) for " + request);
        }

        final RaftGroupId requestGroupId = request.getRaftGroupId();
        // use the group id in the first request as the group id of this observer
        final RaftGroupId updated = groupId.updateAndGet(g -> g != null ? g : requestGroupId);

        if (!requestGroupId.equals(updated)) {
          final GroupMismatchException exception = new GroupMismatchException(getId()
              + ": The group (" + requestGroupId + ") of " + request.getClientId()
              + " does not match the group (" + updated + ") of the " + JavaUtils.getClassSimpleName(getClass()));
          responseError(exception, () -> "processClientRequest (Group mismatched) for " + request);
          return;
        }
        final PendingOrderedRequest pending = new PendingOrderedRequest(requestRef);
        try {
          slidingWindow.receivedRequest(pending, this::processClientRequest);
        } catch (Exception e) {
          pending.release();
          throw e;
        }
      } finally {
        requestRef.release();
      }
    }

    private void sendReply(PendingOrderedRequest ready) {
      Preconditions.assertTrue(ready.hasReply());
      if (ready == COMPLETED) {
        close(true);
      } else {
        LOG.debug("{}: sendReply seq={}, {}", getName(), ready.getSeqNum(), ready.getReply());
        responseNext(ClientProtoUtils.toRaftClientReplyProto(ready.getReply()));
      }
    }

    @Override
    public void onError(Throwable t) {
      // for now we just log a msg
      GrpcUtil.warn(LOG, () -> getName() + ": onError", t);
      close(false);
    }

    @Override
    public void onCompleted() {
      if (slidingWindow.endOfRequests(this::sendReply)) {
        close(true);
      }
    }

    private void close(boolean complete) {
      if (setClose()) {
        LOG.debug("{}: close", getName());
        if (complete) {
          responseCompleted();
        }
        cleanup();
      }
    }

    private void cleanup() {
      slidingWindow.close();
      orderedStreamObservers.removeExisting(this);
    }

    @Override
    boolean responseError(Throwable t, Supplier<String> message) {
      if (super.responseError(t, message)) {
        cleanup();
        return true;
      }
      return false;
    }
  }
}
