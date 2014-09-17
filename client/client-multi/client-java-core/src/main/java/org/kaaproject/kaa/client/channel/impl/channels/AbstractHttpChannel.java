/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kaaproject.kaa.client.channel.impl.channels;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kaaproject.kaa.client.AbstractKaaClient;
import org.kaaproject.kaa.client.channel.AbstractServerInfo;
import org.kaaproject.kaa.client.channel.ChannelDirection;
import org.kaaproject.kaa.client.channel.KaaDataChannel;
import org.kaaproject.kaa.client.channel.KaaDataDemultiplexer;
import org.kaaproject.kaa.client.channel.KaaDataMultiplexer;
import org.kaaproject.kaa.client.channel.ServerInfo;
import org.kaaproject.kaa.client.channel.connectivity.ConnectivityChecker;
import org.kaaproject.kaa.client.persistence.KaaClientState;
import org.kaaproject.kaa.client.transport.AbstractHttpClient;
import org.kaaproject.kaa.common.TransportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractHttpChannel implements KaaDataChannel {
    public static final Logger LOG = LoggerFactory //NOSONAR
            .getLogger(AbstractHttpChannel.class);

    private AbstractServerInfo currentServer;
    private final AbstractKaaClient client;
    private final KaaClientState state;

    private ExecutorService executor;

    private volatile boolean lastConnectionFailed = false;
    private volatile boolean isShutdown = false;
    private volatile boolean isPaused = false;

    private AbstractHttpClient httpClient;
    private KaaDataDemultiplexer demultiplexer;
    private KaaDataMultiplexer multiplexer;

    public AbstractHttpChannel(AbstractKaaClient client, KaaClientState state) {
        this.client = client;
        this.state = state;
    }

    protected ExecutorService createExecutor() {
        LOG.info("Creating a new executor for channel {}", getId());
        return Executors.newSingleThreadExecutor();
    }

    @Override
    public synchronized void sync(TransportType type) {
        if (isShutdown) {
            LOG.info("Can't sync. Channel {} is down", getId());
            return;
        }
        if (isPaused) {
            LOG.info("Can't sync. Channel {} is paused", getId());
            return;
        }
        LOG.info("Processing sync {} for channel {}", type, getId());
        if (multiplexer != null && demultiplexer != null) {
            if (currentServer != null) {
                ChannelDirection direction = getSupportedTransportTypes().get(type);
                if (direction != null) {
                    Map<TransportType, ChannelDirection> typeMap = new HashMap<>(1);
                    typeMap.put(type, direction);
                    executor.submit(createChannelRunnable(typeMap));
                } else {
                    LOG.error("Unsupported type {} for channel {}", type, getId());
                }
            } else {
                lastConnectionFailed = true;
                LOG.warn("Can't sync. Server is null");
            }
        }
    }

    @Override
    public synchronized void syncAll() {
        if (isShutdown) {
            LOG.info("Can't sync. Channel {} is down", getId());
            return;
        }
        if (isPaused) {
            LOG.info("Can't sync. Channel {} is paused", getId());
            return;
        }
        LOG.info("Processing sync all for channel {}", getId());
        if (multiplexer != null && demultiplexer != null) {
            if (currentServer != null) {
                executor.submit(createChannelRunnable(getSupportedTransportTypes()));
            } else {
                lastConnectionFailed = true;
                LOG.warn("Can't sync. Server is null");
            }
        }
    }

    @Override
    public void syncAck(TransportType type) {
        LOG.info("Sync ack message is ignored for Channel {}", getId());
    }

    @Override
    public synchronized void setDemultiplexer(KaaDataDemultiplexer demultiplexer) {
        if (demultiplexer != null) {
            this.demultiplexer = demultiplexer;
        }
    }

    @Override
    public synchronized void setMultiplexer(KaaDataMultiplexer multiplexer) {
        if (multiplexer != null) {
            this.multiplexer = multiplexer;
        }
    }

    @Override
    public synchronized void setServer(ServerInfo server) {
        if (isShutdown) {
            LOG.info("Can't set server. Channel {} is down", getId());
            return;
        }
        if (executor == null) {
            executor = createExecutor();
        }
        if (server != null) {
            this.currentServer = (AbstractServerInfo) server;
            this.httpClient = client.createHttpClient(currentServer.getURL(), state.getPrivateKey(), state.getPublicKey(), currentServer.getPublicKey());
            if (lastConnectionFailed && !isPaused) {
                lastConnectionFailed = false;
                syncAll();
            }
        }
    }

    @Override
    public void setConnectivityChecker(ConnectivityChecker checker) {}

    public void shutdown() {
        isShutdown = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public void pause() {
        isPaused = true;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void resume() {
        isPaused = false;
        if (lastConnectionFailed) {
            lastConnectionFailed = false;
            syncAll();
        }
    }

    protected void connectionFailed(boolean failed) {
        lastConnectionFailed = failed;
        if (failed) {
            client.getChannelMananager().onServerFailed(currentServer);
        }
    }

    protected KaaDataMultiplexer getMultiplexer() {
        return multiplexer;
    }

    protected KaaDataDemultiplexer getDemultiplexer() {
        return demultiplexer;
    }

    protected AbstractHttpClient getHttpClient() {
        return httpClient;
    }

    protected abstract Runnable createChannelRunnable(Map<TransportType, ChannelDirection> typeMap);

}