/**
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

package org.apache.ratis.grpc.metrics.intercept.client;

import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import org.apache.ratis.grpc.metrics.MessageMetrics;

public class MetricClientCall<R, S> extends ForwardingClientCall.SimpleForwardingClientCall<R, S> {
  private final String metricNamePrefix;
  private final MessageMetrics metrics;

  public MetricClientCall(ClientCall<R, S> delegate,
                          MessageMetrics metrics,
                          String metricName){
    super(delegate);
    this.metricNamePrefix = metricName;
    this.metrics = metrics;
  }

  @Override
  public void start(ClientCall.Listener<S> delegate, Metadata metadata) {
    metrics.rpcStarted(metricNamePrefix);
    super.start(new MetricClientCallListener<>(
        delegate, metrics, metricNamePrefix), metadata);
  }
}
