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
package org.apache.ratis.netty;

import org.apache.ratis.security.TlsConf;
import org.apache.ratis.security.TlsConf.CertificatesConf;
import org.apache.ratis.security.TlsConf.KeyManagerConf;
import org.apache.ratis.security.TlsConf.PrivateKeyConf;
import org.apache.ratis.security.TlsConf.TrustManagerConf;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.ratis.util.ConcurrentUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public interface NettyUtils {
  Logger LOG = LoggerFactory.getLogger(NettyUtils.class);

  class Print {
    private static final AtomicBoolean PRINTED_EPOLL_UNAVAILABILITY_CAUSE = new AtomicBoolean();

    private Print() {}

    static void epollUnavailability(String message) {
      if (!LOG.isWarnEnabled()) {
        return;
      }
      if (PRINTED_EPOLL_UNAVAILABILITY_CAUSE.compareAndSet(false, true)) {
        LOG.warn(message, new IllegalStateException("Epoll is unavailable.", Epoll.unavailabilityCause()));
      } else {
        LOG.warn(message);
      }
    }
  }

  static EventLoopGroup newEventLoopGroup(String name, int size, boolean useEpoll) {
    if (useEpoll) {
      if (Epoll.isAvailable()) {
        LOG.info("Create EpollEventLoopGroup for {}; Thread size is {}.", name, size);
        return new EpollEventLoopGroup(size, ConcurrentUtils.newThreadFactory(name + "-"));
      } else {
        Print.epollUnavailability("Failed to create EpollEventLoopGroup for " + name
            + "; fall back on NioEventLoopGroup.");
      }
    }
    return new NioEventLoopGroup(size, ConcurrentUtils.newThreadFactory(name + "-"));
  }

  static void setTrustManager(SslContextBuilder b, TrustManagerConf trustManagerConfig) {
    if (trustManagerConfig == null) {
      return;
    }
    final TrustManager trustManager = trustManagerConfig.getTrustManager();
    if (trustManager != null) {
      b.trustManager(trustManager);
      return;
    }
    final CertificatesConf certificates = trustManagerConfig.getTrustCertificates();
    if (certificates.isFileBased()) {
      b.trustManager(certificates.getFile());
    } else {
      b.trustManager(certificates.get());
    }
  }

  static void setKeyManager(SslContextBuilder b, KeyManagerConf keyManagerConfig) {
    if (keyManagerConfig == null) {
      return;
    }
    final KeyManager keyManager = keyManagerConfig.getKeyManager();
    if (keyManager != null) {
      b.keyManager(keyManager);
      return;
    }
    final PrivateKeyConf privateKey = keyManagerConfig.getPrivateKey();
    final CertificatesConf certificates = keyManagerConfig.getKeyCertificates();

    if (keyManagerConfig.isFileBased()) {
      b.keyManager(certificates.getFile(), privateKey.getFile());
    } else {
      b.keyManager(privateKey.get(), certificates.get());
    }
  }

  static SslContextBuilder initSslContextBuilderForServer(KeyManagerConf keyManagerConfig) {
    final KeyManager keyManager = keyManagerConfig.getKeyManager();
    if (keyManager != null) {
      return SslContextBuilder.forServer(keyManager);
    }
    final PrivateKeyConf privateKey = keyManagerConfig.getPrivateKey();
    final CertificatesConf certificates = keyManagerConfig.getKeyCertificates();

    if (keyManagerConfig.isFileBased()) {
      return SslContextBuilder.forServer(certificates.getFile(), privateKey.getFile());
    } else {
      return SslContextBuilder.forServer(privateKey.get(), certificates.get());
    }
  }

  static SslContextBuilder initSslContextBuilderForServer(TlsConf tlsConf) {
    final SslContextBuilder b = initSslContextBuilderForServer(tlsConf.getKeyManager());
    if (tlsConf.isMutualTls()) {
      setTrustManager(b, tlsConf.getTrustManager());
    }
    return b;
  }

  static SslContext buildSslContextForServer(TlsConf tlsConf) {
    return buildSslContext("server", tlsConf, NettyUtils::initSslContextBuilderForServer);
  }

  static SslContextBuilder initSslContextBuilderForClient(TlsConf tlsConf) {
    final SslContextBuilder b = SslContextBuilder.forClient();
    setTrustManager(b, tlsConf.getTrustManager());
    if (tlsConf.isMutualTls()) {
      setKeyManager(b, tlsConf.getKeyManager());
    }
    return b;
  }

  static SslContext buildSslContextForClient(TlsConf tlsConf) {
    return buildSslContext("client", tlsConf, NettyUtils::initSslContextBuilderForClient);
  }

  static SslContext buildSslContext(String name, TlsConf tlsConf, Function<TlsConf, SslContextBuilder> builder) {
    if (tlsConf == null) {
      return null;
    }
    final SslContext sslContext;
    try {
      sslContext = builder.apply(tlsConf).build();
    } catch (Exception e) {
      final String message = "Failed to buildSslContext for " + name + " from " + tlsConf;
      throw new IllegalArgumentException(message, e);
    }
    LOG.debug("buildSslContext for {} from {} returns {}", name, tlsConf, sslContext.getClass().getName());
    return sslContext;
  }

  static Class<? extends SocketChannel> getSocketChannelClass(EventLoopGroup eventLoopGroup) {
    return eventLoopGroup instanceof EpollEventLoopGroup ?
        EpollSocketChannel.class : NioSocketChannel.class;
  }

  static Class<? extends ServerChannel> getServerChannelClass(EventLoopGroup eventLoopGroup) {
    return eventLoopGroup instanceof EpollEventLoopGroup ?
        EpollServerSocketChannel.class : NioServerSocketChannel.class;
  }
}