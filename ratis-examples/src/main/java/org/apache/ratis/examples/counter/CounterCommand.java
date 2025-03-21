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
package org.apache.ratis.examples.counter;

import org.apache.ratis.protocol.Message;
import com.google.protobuf.ByteString;

/**
 * The supported commands the Counter example.
 */
public enum CounterCommand {
  /** Increment the counter by 1. */
  INCREMENT,
  /** Get the counter value. */
  GET;

  private final Message message = Message.valueOf(name());

  public Message getMessage() {
    return message;
  }

  /** Does the given command string match this command? */
  public boolean matches(String command) {
    return name().equalsIgnoreCase(command);
  }

  /** Does the given command string match this command? */
  public boolean matches(ByteString command) {
    return message.getContent().equals(command);
  }
}
