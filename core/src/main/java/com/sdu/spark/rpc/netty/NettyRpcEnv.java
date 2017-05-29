package com.sdu.spark.rpc.netty;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sdu.spark.SecurityManager;
import com.sdu.spark.network.TransportContext;
import com.sdu.spark.network.client.TransportClient;
import com.sdu.spark.network.client.TransportClientBootstrap;
import com.sdu.spark.network.client.TransportClientFactory;
import com.sdu.spark.network.crypto.AuthServerBootstrap;
import com.sdu.spark.network.server.StreamManager;
import com.sdu.spark.network.server.TransportServer;
import com.sdu.spark.network.server.TransportServerBootstrap;
import com.sdu.spark.network.utils.IOModel;
import com.sdu.spark.network.utils.TransportConf;
import com.sdu.spark.rpc.*;
import com.sdu.spark.rpc.netty.OutboxMessage.*;
import com.sdu.spark.rpc.netty.OutboxMessage.CheckExistence;
import com.sdu.spark.rpc.netty.OutboxMessage.OneWayOutboxMessage;
import com.sdu.spark.utils.ThreadUtils;
import com.sdu.spark.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author hanhan.zhang
 * */
public class NettyRpcEnv extends RpcEnv {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRpcEnv.class);

    private String host;

    private JSparkConfig conf;
    /**
     * Spark权限管理模块
     * */
    private SecurityManager securityManager;
    /**
     * RpcEnv Server端, 负责网络数据传输
     * */
    private TransportServer server;
    /**
     * 消息路由分发
     * */
    private Dispatcher dispatcher;
    /**
     * 消息发送信箱[key = 待接收地址, value = 发送信箱]
     * */
    private Map<RpcAddress, Outbox> outboxes = Maps.newConcurrentMap();
    /**
     * 远端服务连接线程
     * */
    private ThreadPoolExecutor clientConnectionExecutor;
    /**
     * 投递消息线程
     * */
    private ThreadPoolExecutor deliverMessageExecutor;
    /**
     * Netty通信上下文
     * */
    private TransportContext transportContext;
    private TransportClientFactory clientFactory;

    private AtomicBoolean stopped = new AtomicBoolean(false);

    public NettyRpcEnv(JSparkConfig sparkConfig, String host, SecurityManager securityManager) {
        this.conf = sparkConfig;
        this.host = host;
        this.dispatcher = new Dispatcher(this, sparkConfig);
        this.clientConnectionExecutor = ThreadUtils.newDaemonCachedThreadPool("netty-rpc-connect-%d", sparkConfig.getRpcConnectThreads(), 60);
        this.deliverMessageExecutor = ThreadUtils.newDaemonCachedThreadPool("rpc-deliver-message-%d", sparkConfig.getDeliverThreads(), 60);
        StreamManager streamManager = null;
        this.transportContext = new TransportContext(fromSparkConf(sparkConfig), new NettyRpcHandler(streamManager, this.dispatcher, this));
        this.clientFactory = this.transportContext.createClientFactory(createClientBootstraps());
        this.securityManager = securityManager;
    }

    @Override
    public RpcAddress address() {
        return server != null ? new RpcAddress(host, server.getPort()) : null;
    }


    /****************************RpcEndPoint节点注册****************************/
    @Override
    public RpcEndPointRef endPointRef(RpcEndPoint endPoint) {
        return dispatcher.getRpcEndPointRef(endPoint);
    }

    @Override
    public RpcEndPointRef setRpcEndPointRef(String name, RpcEndPoint endPoint) {
        return dispatcher.registerRpcEndPoint(name, endPoint);
    }

    @Override
    public RpcEndPointRef setRpcEndPointRef(String name, RpcAddress rpcAddress) {
        NettyRpcEndPointRef verifier = new NettyRpcEndPointRef(RpcEndpointVerifier.NAME, rpcAddress, this);
        Future<?> future =  verifier.ask(new CheckExistence(name));
        return Utils.getFutureResult(future);
    }


    /*******************************Rpc消息发送***********************************/
    /**
     * 单向消息[即不需要消息响应]
     * */
    public void send(RequestMessage message) {
        RpcAddress address = message.receiver.address();
        if (address() == address) { // 发送给本地的消息

        } else { // 发送给远端的消息
            postToOutbox(message.receiver, new OneWayOutboxMessage(message.serialize()));
        }
    }

    /**
     * 双向消息[需要消息响应]
     * */
    public Future<?> ask(RequestMessage message) {
        if (message.receiver.address().equals(address())) {
            // 发送本地消息
            return deliverMessageExecutor.submit(() -> {
                try {
                    dispatcher.postLocalMessage(message);
                } catch (ExecutionException e) {
                    LOGGER.error("post local message error", e);
                } catch (RejectedExecutionException e) {
                    LOGGER.error("deliver message thread reject local message task", e);
                } catch (InterruptedException e) {
                    LOGGER.error("await local message process thread interrupt", e);
                }
            });
        } else {
            // 发送网络消息
            NettyRpcResponseCallback callback = new NettyRpcResponseCallback();
            OutboxMessage.RpcOutboxMessage outboxMessage = new RpcOutboxMessage(message.serialize(), callback);
            postToOutbox(message.receiver, outboxMessage);
            return callback.getResponseFuture();
        }
    }

    private void postToOutbox(NettyRpcEndPointRef receiver, OutboxMessage message) {
        if (receiver.getClient() != null) {
            message.sendWith(receiver.getClient());
        } else {
            if (receiver.address() == null) {
                throw new IllegalStateException("Cannot send message to client endpoint with no listen address.");
            }
            Outbox outbox = outboxes.get(receiver.address());
            if (outbox == null) {
                Outbox newOutbox = new Outbox(this, receiver.address());
                Outbox oldOutbox = outboxes.putIfAbsent(receiver.address(), newOutbox);
                if (oldOutbox == null) {
                    outbox = newOutbox;
                } else {
                    outbox = oldOutbox;
                }
            }
            if (stopped.get()) {
                outboxes.remove(receiver.address());
                outbox.stop();
            } else {
                outbox.send(message);
            }
        }
    }

    public void removeOutbox(RpcAddress address) {
        Outbox outbox = outboxes.remove(address);
        if (outbox != null) {
            outbox.stop();
        }
    }

    /*****************************Rpca启动********************************/
    public void startServer(String host, int port) {
        List<TransportServerBootstrap> bootstraps;
        if (securityManager.isAuthenticationEnabled()) {
            bootstraps = Lists.newArrayList(new AuthServerBootstrap(fromSparkConf(conf), securityManager));
        } else {
            bootstraps = Collections.emptyList();
        }
        server = transportContext.createServer(host, port, bootstraps);
        // 注册RpcEndPoint节点
        dispatcher.registerRpcEndPoint(RpcEndpointVerifier.NAME, new RpcEndpointVerifier(this, dispatcher));
    }

    public TransportClient createClient(RpcAddress address) {
        try {
            return clientFactory.createClient(address.host, address.port);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

    }

    public Future<TransportClient> asyncCreateClient(RpcAddress address) {
        return clientConnectionExecutor.submit(() -> createClient(address));
    }

    @Override
    public void awaitTermination() {
        dispatcher.awaitTermination();
    }

    @Override
    public void stop(RpcEndPoint endPoint) {

    }

    @Override
    public void shutdown() {

    }

    private TransportConf fromSparkConf(JSparkConfig conf) {
        return new TransportConf(IOModel.NIO.name(), Collections.emptyMap());
    }

    private List<TransportClientBootstrap> createClientBootstraps() {
        return Collections.emptyList();
    }

}
