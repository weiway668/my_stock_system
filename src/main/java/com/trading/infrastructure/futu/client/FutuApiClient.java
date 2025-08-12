package com.trading.infrastructure.futu.client;

import com.trading.infrastructure.futu.protocol.FutuProtocol;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FUTU API客户端
 * 负责与FUTU OpenD建立TCP连接并进行协议通信
 */
@Slf4j
@Component
public class FutuApiClient {
    
    // 连接配置
    private String host = "127.0.0.1";
    private int port = 11111;
    private int connectTimeoutMs = 5000;
    private int requestTimeoutMs = 10000;
    
    // Netty组件
    private EventLoopGroup workerGroup;
    private Bootstrap bootstrap;
    private Channel channel;
    private ChannelFuture connectFuture;
    
    // 请求管理
    private final AtomicInteger serialNoGenerator = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<ByteBuf>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Integer, ProtocolCallback> protocolCallbacks = new ConcurrentHashMap<>();
    
    // 连接状态
    private volatile boolean isConnected = false;
    private final AtomicLong lastHeartbeatTime = new AtomicLong(0);
    private ScheduledExecutorService scheduledExecutor;
    
    // 推送处理器
    private final Map<Integer, PushHandler> pushHandlers = new ConcurrentHashMap<>();
    
    /**
     * 协议回调接口
     */
    public interface ProtocolCallback {
        void onResponse(int protoId, ByteBuf data);
        void onError(int protoId, int errorCode, String errorMsg);
    }
    
    /**
     * 推送处理器接口
     */
    public interface PushHandler {
        void handlePush(ByteBuf data);
    }
    
    @PostConstruct
    public void init() {
        log.info("初始化FUTU API客户端...");
        
        workerGroup = new NioEventLoopGroup(4);
        bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    
                    // 添加长度字段解码器，处理FUTU协议包
                    pipeline.addLast(new LengthFieldBasedFrameDecoder(
                        1024 * 1024,  // 最大包长度1MB
                        4,            // 长度字段偏移量（跳过"FT"和协议ID）
                        4,            // 长度字段长度
                        36,           // 长度字段后还有36字节的头部
                        0             // 不跳过任何字节
                    ));
                    
                    // 添加空闲状态处理器，用于心跳
                    pipeline.addLast(new IdleStateHandler(0, 30, 0));
                    
                    // 添加协议处理器
                    pipeline.addLast(new FutuProtocolHandler());
                }
            });
        
        scheduledExecutor = Executors.newScheduledThreadPool(2);
        
        // 注册推送处理器
        registerPushHandlers();
    }
    
    /**
     * 连接到FUTU OpenD
     */
    public CompletableFuture<Boolean> connect() {
        return connect(this.host, this.port);
    }
    
    /**
     * 连接到指定的FUTU OpenD
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (isConnected) {
            future.complete(true);
            return future;
        }
        
        this.host = host;
        this.port = port;
        
        log.info("连接到FUTU OpenD: {}:{}", host, port);
        
        connectFuture = bootstrap.connect(host, port);
        connectFuture.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                channel = f.channel();
                isConnected = true;
                log.info("成功连接到FUTU OpenD");
                
                // 发送初始化连接协议
                initConnection().thenAccept(success -> {
                    if (success) {
                        // 启动心跳
                        startHeartbeat();
                        future.complete(true);
                    } else {
                        future.complete(false);
                    }
                });
            } else {
                log.error("连接FUTU OpenD失败", f.cause());
                isConnected = false;
                future.complete(false);
            }
        });
        
        return future;
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        log.info("断开FUTU OpenD连接");
        
        if (channel != null && channel.isActive()) {
            channel.close();
        }
        
        isConnected = false;
        
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
    }
    
    /**
     * 发送请求
     */
    public CompletableFuture<ByteBuf> sendRequest(int protoId, byte[] data) {
        CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        
        if (!isConnected || channel == null || !channel.isActive()) {
            future.completeExceptionally(new RuntimeException("未连接到FUTU OpenD"));
            return future;
        }
        
        int serialNo = serialNoGenerator.getAndIncrement();
        
        // 构建消息头
        ByteBuf headerBuf = Unpooled.buffer(FutuProtocol.MessageHeader.HEADER_SIZE);
        headerBuf.writeBytes(FutuProtocol.MessageHeader.HEADER_FLAG); // "FT"
        headerBuf.writeIntLE(protoId);
        headerBuf.writeByte(0); // Protobuf格式
        headerBuf.writeByte(FutuProtocol.PROTOCOL_VERSION);
        headerBuf.writeIntLE(serialNo);
        headerBuf.writeIntLE(data != null ? data.length : 0);
        headerBuf.writeZero(20); // SHA1占位
        headerBuf.writeZero(8);  // 保留字段
        
        // 构建完整消息
        ByteBuf messageBuf = Unpooled.buffer();
        messageBuf.writeBytes(headerBuf);
        if (data != null && data.length > 0) {
            messageBuf.writeBytes(data);
        }
        
        // 保存待响应请求
        pendingRequests.put(serialNo, future);
        
        // 设置超时
        scheduledExecutor.schedule(() -> {
            CompletableFuture<ByteBuf> pending = pendingRequests.remove(serialNo);
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new TimeoutException("请求超时"));
            }
        }, requestTimeoutMs, TimeUnit.MILLISECONDS);
        
        // 发送消息
        channel.writeAndFlush(messageBuf).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                pendingRequests.remove(serialNo);
                future.completeExceptionally(f.cause());
            }
        });
        
        return future;
    }
    
    /**
     * 初始化连接
     */
    private CompletableFuture<Boolean> initConnection() {
        log.debug("发送初始化连接请求");
        
        // 构建初始化请求（简化版）
        byte[] initData = buildInitConnectRequest();
        
        return sendRequest(FutuProtocol.PROTO_ID_INIT_CONNECT, initData)
            .thenApply(response -> {
                log.info("初始化连接成功");
                return true;
            })
            .exceptionally(ex -> {
                log.error("初始化连接失败", ex);
                return false;
            });
    }
    
    /**
     * 构建初始化连接请求
     */
    private byte[] buildInitConnectRequest() {
        // TODO: 实现protobuf序列化
        // 这里暂时返回空数据，实际需要按照FUTU协议构建
        return new byte[0];
    }
    
    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            if (isConnected && channel != null && channel.isActive()) {
                sendHeartbeat();
            }
        }, 10, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        log.trace("发送心跳");
        sendRequest(FutuProtocol.PROTO_ID_HEARTBEAT, null)
            .thenAccept(response -> {
                lastHeartbeatTime.set(System.currentTimeMillis());
                log.trace("心跳响应成功");
            })
            .exceptionally(ex -> {
                log.warn("心跳失败", ex);
                return null;
            });
    }
    
    /**
     * 注册推送处理器
     */
    private void registerPushHandlers() {
        // 注册行情推送处理器
        pushHandlers.put(FutuProtocol.PROTO_ID_PUSH_QUOTE_UPDATE, data -> {
            log.debug("收到行情推送");
            // TODO: 处理行情推送
        });
        
        // 注册K线推送处理器
        pushHandlers.put(FutuProtocol.PROTO_ID_PUSH_KLINE_UPDATE, data -> {
            log.debug("收到K线推送");
            // TODO: 处理K线推送
        });
        
        // 注册订单推送处理器
        pushHandlers.put(FutuProtocol.PROTO_ID_PUSH_ORDER_UPDATE, data -> {
            log.debug("收到订单更新推送");
            // TODO: 处理订单推送
        });
    }
    
    /**
     * Netty协议处理器
     */
    private class FutuProtocolHandler extends SimpleChannelInboundHandler<ByteBuf> {
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            try {
                // 读取消息头
                if (msg.readableBytes() < FutuProtocol.MessageHeader.HEADER_SIZE) {
                    log.error("消息头长度不足");
                    return;
                }
                
                // 验证包头标识
                byte flag1 = msg.readByte();
                byte flag2 = msg.readByte();
                if (flag1 != 'F' || flag2 != 'T') {
                    log.error("无效的包头标识");
                    return;
                }
                
                // 读取协议ID和序列号
                int protoId = msg.readIntLE();
                msg.readByte(); // 格式类型
                msg.readByte(); // 协议版本
                int serialNo = msg.readIntLE();
                int bodyLen = msg.readIntLE();
                msg.skipBytes(28); // 跳过SHA1和保留字段
                
                // 读取包体
                ByteBuf bodyBuf = null;
                if (bodyLen > 0 && msg.readableBytes() >= bodyLen) {
                    bodyBuf = msg.readSlice(bodyLen);
                }
                
                // 处理推送消息
                if (serialNo == 0) {
                    PushHandler handler = pushHandlers.get(protoId);
                    if (handler != null) {
                        handler.handlePush(bodyBuf);
                    }
                } else {
                    // 处理响应消息
                    CompletableFuture<ByteBuf> future = pendingRequests.remove(serialNo);
                    if (future != null) {
                        future.complete(bodyBuf);
                    }
                }
                
            } catch (Exception e) {
                log.error("处理消息异常", e);
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.warn("连接断开");
            isConnected = false;
            
            // 清理待响应请求
            pendingRequests.forEach((serialNo, future) -> {
                future.completeExceptionally(new RuntimeException("连接断开"));
            });
            pendingRequests.clear();
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("通道异常", cause);
            ctx.close();
        }
    }
    
    @PreDestroy
    public void destroy() {
        disconnect();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
    
    // Getter methods
    public boolean isConnected() {
        return isConnected;
    }
    
    public long getLastHeartbeatTime() {
        return lastHeartbeatTime.get();
    }
}