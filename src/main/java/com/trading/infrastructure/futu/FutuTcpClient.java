package com.trading.infrastructure.futu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors;

/**
 * FUTU TCP客户端
 * 基于Python futu-api的实现方式，使用原始TCP Socket连接
 * 参考: https://github.com/FutunnOpen/py-futu-api
 */
@Slf4j
@Component
public class FutuTcpClient {
    
    // 协议常量
    private static final int HEADER_SIZE = 44;  // 协议头部固定长度
    private static final byte[] INIT_CONNECT_MAGIC = "FT".getBytes();  // 初始连接魔数
    
    // 协议ID定义（参考Python版本）
    private static final int PROTO_ID_INIT_CONNECT = 1001;  // 初始化连接
    private static final int PROTO_ID_GET_GLOBAL_STATE = 1002;  // 获取全局状态
    private static final int PROTO_ID_KEEP_ALIVE = 1004;  // 心跳
    private static final int PROTO_ID_QOT_GET_KLINE = 3103;  // 获取K线
    private static final int PROTO_ID_QOT_SUB = 3001;  // 订阅
    private static final int PROTO_ID_QOT_REG_PUSH = 3002;  // 注册推送
    
    // 连接状态
    private Socket socket;
    private DataOutputStream outputStream;
    private DataInputStream inputStream;
    private volatile boolean isConnected = false;
    
    // 请求管理
    private final AtomicInteger serialNoGenerator = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<>();
    
    // 接收线程
    private Thread receiveThread;
    
    /**
     * 连接到FUTU OpenD
     */
    public boolean connect(String host, int port) {
        try {
            log.info("连接到FUTU OpenD: {}:{}", host, port);
            
            // 建立TCP连接
            socket = new Socket(host, port);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            
            outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            
            // 发送初始化连接请求
            if (!sendInitConnect()) {
                log.error("初始化连接失败");
                disconnect();
                return false;
            }
            
            isConnected = true;
            
            // 启动接收线程
            startReceiveThread();
            
            // 启动心跳
            startKeepAlive();
            
            log.info("成功连接到FUTU OpenD");
            return true;
            
        } catch (Exception e) {
            log.error("连接FUTU OpenD失败", e);
            return false;
        }
    }
    
    /**
     * 发送初始化连接请求
     */
    private boolean sendInitConnect() {
        try {
            // 构造初始化连接请求
            int serialNo = serialNoGenerator.getAndIncrement();
            
            // 创建请求体
            byte[] body = createInitConnectBody();
            log.info("InitConnect消息体长度: {} 字节", body.length);
            
            // 打印消息体的十六进制表示（用于调试）
            StringBuilder hex = new StringBuilder();
            for (byte b : body) {
                hex.append(String.format("%02X ", b));
            }
            log.debug("InitConnect消息体(HEX): {}", hex.toString());
            
            // 发送请求
            sendPacket(PROTO_ID_INIT_CONNECT, serialNo, body);
            
            // 等待响应（增加超时时间）
            log.info("等待InitConnect响应...");
            Thread.sleep(1000); // 等待1秒让服务器处理
            
            // 尝试读取响应
            if (inputStream.available() > 0) {
                byte[] response = readPacket();
                if (response != null) {
                    log.info("收到初始化连接响应，长度: {} 字节", response.length);
                    // 解析响应
                    parseInitConnectResponse(response);
                    return true;
                }
            } else {
                log.warn("没有收到响应数据");
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("发送初始化连接请求失败", e);
            return false;
        }
    }
    
    /**
     * 解析InitConnect响应
     */
    private void parseInitConnectResponse(byte[] response) {
        try {
            log.info("解析InitConnect响应...");
            // 简单解析，查找retType字段
            // Protobuf的retType字段通常在开始位置
            if (response.length > 0) {
                int retType = response[0]; // 简化解析
                log.info("响应retType: {}", retType);
                if (retType == 0) {
                    log.info("InitConnect成功！");
                } else {
                    log.warn("InitConnect失败，错误码: {}", retType);
                }
            }
        } catch (Exception e) {
            log.error("解析响应失败", e);
        }
    }
    
    /**
     * 创建初始化连接请求体
     */
    private byte[] createInitConnectBody() {
        try {
            // 手动构造Protobuf消息（InitConnect.C2S）
            // 使用Google Protobuf API动态构造
            com.google.protobuf.DynamicMessage.Builder c2sBuilder = 
                com.google.protobuf.DynamicMessage.newBuilder(getInitConnectC2SDescriptor());
            
            // 设置必需字段
            c2sBuilder.setField(c2sBuilder.getDescriptorForType().findFieldByNumber(1), 100);  // clientVer
            c2sBuilder.setField(c2sBuilder.getDescriptorForType().findFieldByNumber(2), "JavaClient_1.0");  // clientID
            c2sBuilder.setField(c2sBuilder.getDescriptorForType().findFieldByNumber(3), false);  // recvNotify
            c2sBuilder.setField(c2sBuilder.getDescriptorForType().findFieldByNumber(4), 0);  // packetEncAlgo (不加密)
            c2sBuilder.setField(c2sBuilder.getDescriptorForType().findFieldByNumber(5), 0);  // pushProtoFmt (protobuf)
            c2sBuilder.setField(c2sBuilder.getDescriptorForType().findFieldByNumber(6), "Java");  // programmingLanguage
            
            // 包装到Request消息
            com.google.protobuf.DynamicMessage.Builder requestBuilder = 
                com.google.protobuf.DynamicMessage.newBuilder(getInitConnectRequestDescriptor());
            requestBuilder.setField(requestBuilder.getDescriptorForType().findFieldByNumber(1), c2sBuilder.build());
            
            return requestBuilder.build().toByteArray();
            
        } catch (Exception e) {
            log.error("创建InitConnect请求失败，使用简化版本", e);
            
            // 如果动态构造失败，使用手动构造的简化版本
            // 基于Python版本的观察，构造一个最小的InitConnect消息
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            
            try {
                // 构造完整的InitConnect Request消息
                // Request消息包含一个C2S字段
                
                // 首先构造C2S消息
                ByteArrayOutputStream c2sStream = new ByteArrayOutputStream();
                DataOutputStream c2sDos = new DataOutputStream(c2sStream);
                
                // C2S消息字段：
                // Field 1: clientVer (int32) = 100
                c2sDos.write(0x08);  // field 1, wire type 0 (varint)
                c2sDos.write(0x64);  // value 100
                
                // Field 2: clientID (string) = "JavaClient_1.0"  
                String clientId = "JavaClient_1.0";
                c2sDos.write(0x12);  // field 2, wire type 2 (length-delimited)
                writeVarint(c2sDos, clientId.length());
                c2sDos.write(clientId.getBytes("UTF-8"));
                
                // Field 3: recvNotify (bool) = false
                c2sDos.write(0x18);  // field 3, wire type 0 (varint)
                c2sDos.write(0x00);  // value false
                
                // Field 4: packetEncAlgo (int32) = 0 (不加密)
                c2sDos.write(0x20);  // field 4, wire type 0 (varint)
                c2sDos.write(0x00);  // value 0
                
                // Field 5: pushProtoFmt (int32) = 0 (protobuf)
                c2sDos.write(0x28);  // field 5, wire type 0 (varint)
                c2sDos.write(0x00);  // value 0
                
                // Field 6: programmingLanguage (string) = "Java"
                String language = "Java";
                c2sDos.write(0x32);  // field 6, wire type 2 (length-delimited)
                writeVarint(c2sDos, language.length());
                c2sDos.write(language.getBytes("UTF-8"));
                
                c2sDos.flush();
                byte[] c2sBytes = c2sStream.toByteArray();
                
                // 现在构造Request消息，包含C2S作为field 1
                // Request { c2s = C2S }
                dos.write(0x0A);  // field 1 (c2s), wire type 2 (length-delimited)
                writeVarint(dos, c2sBytes.length);
                dos.write(c2sBytes);
                
                dos.flush();
                return baos.toByteArray();
                
            } catch (IOException ioe) {
                log.error("手动构造Protobuf消息失败", ioe);
                return new byte[0];
            }
        }
    }
    
    /**
     * 写入Protobuf varint编码的整数
     */
    private void writeVarint(DataOutputStream dos, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            dos.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dos.write(value & 0x7F);
    }
    
    /**
     * 获取InitConnect C2S消息描述符（占位方法）
     */
    private com.google.protobuf.Descriptors.Descriptor getInitConnectC2SDescriptor() {
        // 这里应该返回实际的Descriptor，但现在返回null触发fallback
        return null;
    }
    
    /**
     * 获取InitConnect Request消息描述符（占位方法）
     */
    private com.google.protobuf.Descriptors.Descriptor getInitConnectRequestDescriptor() {
        // 这里应该返回实际的Descriptor，但现在返回null触发fallback
        return null;
    }
    
    /**
     * 发送数据包
     */
    private void sendPacket(int protoId, int serialNo, byte[] body) throws IOException {
        // 构造包头（参考Python版本的协议格式）
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.order(ByteOrder.LITTLE_ENDIAN);
        
        // 包头魔数 "FT" (2 bytes)
        header.put((byte)'F');
        header.put((byte)'T');
        
        // 协议ID (4 bytes)
        header.putInt(protoId);
        
        // 协议格式类型 0=Protobuf, 1=JSON (1 byte)
        header.put((byte)0);  // 使用Protobuf
        
        // 协议版本 (1 byte)
        header.put((byte)0);
        
        // 序列号 (4 bytes)
        header.putInt(serialNo);
        
        // 包体长度 (4 bytes)
        header.putInt(body != null ? body.length : 0);
        
        // 包体SHA1（20 bytes，这里简化处理）
        for (int i = 0; i < 20; i++) {
            header.put((byte)0);
        }
        
        // 保留字段（8 bytes）
        for (int i = 0; i < 8; i++) {
            header.put((byte)0);
        }
        
        // 发送包头
        outputStream.write(header.array());
        
        // 发送包体
        if (body != null && body.length > 0) {
            outputStream.write(body);
        }
        
        outputStream.flush();
        
        log.debug("发送数据包: protoId={}, serialNo={}, bodyLen={}", 
            protoId, serialNo, body != null ? body.length : 0);
    }
    
    /**
     * 读取数据包
     */
    private byte[] readPacket() throws IOException {
        log.debug("开始读取数据包...");
        
        // 检查是否有数据可读
        int available = inputStream.available();
        log.debug("可读字节数: {}", available);
        
        if (available < HEADER_SIZE) {
            log.warn("可读数据不足，需要{}字节，实际只有{}字节", HEADER_SIZE, available);
            return null;
        }
        
        // 读取包头
        byte[] headerBytes = new byte[HEADER_SIZE];
        inputStream.readFully(headerBytes);
        
        // 打印包头的十六进制（调试用）
        StringBuilder headerHex = new StringBuilder();
        for (int i = 0; i < Math.min(20, headerBytes.length); i++) {
            headerHex.append(String.format("%02X ", headerBytes[i]));
        }
        log.debug("包头前20字节(HEX): {}", headerHex.toString());
        
        ByteBuffer header = ByteBuffer.wrap(headerBytes);
        header.order(ByteOrder.LITTLE_ENDIAN);
        
        // 解析包头
        byte magic1 = header.get();
        byte magic2 = header.get();
        
        if (magic1 != 'F' || magic2 != 'T') {
            log.error("无效的包头魔数: {:02X} {:02X} (期望: 46 54)", magic1, magic2);
            return null;
        }
        
        int protoId = header.getInt();
        byte protoFmtType = header.get();
        byte protoVer = header.get();
        int serialNo = header.getInt();
        int bodyLen = header.getInt();
        
        // 跳过SHA1（20字节）和保留字段（8字节）
        header.position(header.position() + 28);
        
        log.info("收到数据包: protoId={}, serialNo={}, bodyLen={}, protoFmt={}", 
            protoId, serialNo, bodyLen, protoFmtType);
        
        // 读取包体
        byte[] body = null;
        if (bodyLen > 0) {
            body = new byte[bodyLen];
            inputStream.readFully(body);
            
            // 打印包体前20字节（调试用）
            StringBuilder bodyHex = new StringBuilder();
            for (int i = 0; i < Math.min(20, body.length); i++) {
                bodyHex.append(String.format("%02X ", body[i]));
            }
            log.debug("包体前20字节(HEX): {}", bodyHex.toString());
        }
        
        return body;
    }
    
    /**
     * 启动接收线程
     */
    private void startReceiveThread() {
        receiveThread = new Thread(() -> {
            while (isConnected) {
                try {
                    byte[] packet = readPacket();
                    if (packet != null) {
                        // 处理接收到的数据包
                        handleReceivedPacket(packet);
                    }
                } catch (Exception e) {
                    if (isConnected) {
                        log.error("接收数据失败", e);
                    }
                    break;
                }
            }
        }, "FUTU-Receive-Thread");
        
        receiveThread.setDaemon(true);
        receiveThread.start();
    }
    
    /**
     * 处理接收到的数据包
     */
    private void handleReceivedPacket(byte[] packet) {
        // TODO: 解析响应，通知等待的请求
        log.debug("处理接收到的数据包，长度: {}", packet.length);
    }
    
    /**
     * 启动心跳
     */
    private void startKeepAlive() {
        Thread keepAliveThread = new Thread(() -> {
            while (isConnected) {
                try {
                    Thread.sleep(10000);  // 每10秒发送一次心跳
                    sendKeepAlive();
                } catch (Exception e) {
                    log.error("发送心跳失败", e);
                }
            }
        }, "FUTU-KeepAlive-Thread");
        
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }
    
    /**
     * 发送心跳
     */
    private void sendKeepAlive() {
        try {
            int serialNo = serialNoGenerator.getAndIncrement();
            sendPacket(PROTO_ID_KEEP_ALIVE, serialNo, new byte[0]);
            log.trace("发送心跳: serialNo={}", serialNo);
        } catch (Exception e) {
            log.error("发送心跳失败", e);
        }
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        isConnected = false;
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            log.error("关闭连接失败", e);
        }
        
        log.info("已断开与FUTU OpenD的连接");
    }
    
    /**
     * 获取K线数据（示例方法）
     */
    public CompletableFuture<byte[]> getKLine(String symbol, String period, 
                                              String startTime, String endTime) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int serialNo = serialNoGenerator.getAndIncrement();
                
                // TODO: 构造K线请求的Protobuf消息
                byte[] body = new byte[0];  // 需要实现Protobuf序列化
                
                // 创建Future等待响应
                CompletableFuture<byte[]> future = new CompletableFuture<>();
                pendingRequests.put(serialNo, future);
                
                // 发送请求
                sendPacket(PROTO_ID_QOT_GET_KLINE, serialNo, body);
                
                // 等待响应（超时30秒）
                return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                
            } catch (Exception e) {
                log.error("获取K线数据失败", e);
                return null;
            }
        });
    }
    
    /**
     * 测试连接
     */
    public static void main(String[] args) {
        FutuTcpClient client = new FutuTcpClient();
        
        if (client.connect("127.0.0.1", 11111)) {
            System.out.println("连接成功！");
            
            // 保持连接一段时间
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            client.disconnect();
        } else {
            System.out.println("连接失败！");
        }
    }
}