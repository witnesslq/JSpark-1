package com.sdu.spark.rpc.netty;

import com.sdu.spark.network.client.TransportClient;
import com.sdu.spark.rpc.RpcAddress;
import com.sdu.spark.rpc.RpcEndPointRef;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.Future;

/**
 * Note:
 *
 *  1: {@link NettyRpcEndPointRef}归属于{@link NettyRpcEnv}
 *
 *  2: 使用{@link NettyRpcEndPointRef}发送消息时:
 *
 *      1': 发送方地址 = RpcEnv.address[即RpcEnv启动的RpcServer地址]
 *
 *      2': 接收方地址 = {@link NettyRpcEndPointRef#address}
 *
 * @author hanhan.zhang
 * */
public class NettyRpcEndPointRef extends RpcEndPointRef {
    /**
     * 被引用Rpc节点名称
     * */
    private String name;
    /**
     * 被引用Rpc节点的地址
     * */
    private RpcAddress address;
    /**
     * {@link NettyRpcEndPointRef}所属的RpcEnv
     * */
    private NettyRpcEnv rpcEnv;

    @Getter
    @Setter
    private TransportClient client;

    public NettyRpcEndPointRef(String name, RpcAddress address, NettyRpcEnv rpcEnv) {
        this.name = name;
        this.address = address;
        this.rpcEnv = rpcEnv;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RpcAddress address() {
        return address;
    }

    @Override
    public void send(Object message) {
        assert rpcEnv != null;
        rpcEnv.send(new RequestMessage(rpcEnv.address(), this, message));
    }

    @Override
    public Future<?> ask(Object message) {
        assert rpcEnv != null;
        return rpcEnv.ask(new RequestMessage(rpcEnv.address(), this, message));
    }

    @Override
    public Future<?> ask(Object message, int timeout) {
        return null;
    }
}
