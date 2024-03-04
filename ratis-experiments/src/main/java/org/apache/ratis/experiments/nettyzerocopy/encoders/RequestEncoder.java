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

package org.apache.ratis.experiments.nettyzerocopy.encoders;

import org.apache.ratis.experiments.nettyzerocopy.objects.RequestData;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.nio.ByteBuffer;
import java.util.List;


/**
 * Encoder class for {@link RequestData}
 * Writes ID, Length of the buffer and buffer to the outbound message.
 */
public class RequestEncoder
    extends MessageToMessageEncoder<RequestData> {

  @Override
  protected void encode(ChannelHandlerContext channelHandlerContext,
                        RequestData requestData, List<Object> list) throws Exception {
    ByteBuffer bb = ByteBuffer.allocateDirect(8);
    bb.putInt(requestData.getDataId());
    bb.putInt(requestData.getBuff().capacity());
    bb.flip();
    list.add(Unpooled.wrappedBuffer(bb, requestData.getBuff()));
  }
}