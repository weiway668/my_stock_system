package com.trading.infrastructure.futu.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * FUTU Protobuf序列化器
 * 处理FUTU API的Protobuf消息序列化和反序列化
 * 
 * 注意：这是一个简化版本，实际生产环境需要使用FUTU提供的.proto文件生成Java类
 */
@Slf4j
@Component
public class FutuProtobufSerializer {
    
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .setDateFormat("yyyy-MM-dd HH:mm:ss")
        .create();
    
    /**
     * 构建订阅请求
     * 实际应该使用Protobuf的Qot_Sub.Request
     */
    public byte[] buildSubscribeRequest(String symbol, int subType) {
        try {
            // 简化版：使用JSON代替Protobuf
            Map<String, Object> request = new HashMap<>();
            Map<String, Object> c2s = new HashMap<>();
            
            // 构建安全信息
            Map<String, Object> security = new HashMap<>();
            security.put("market", getMarketCode(symbol));
            security.put("code", getStockCode(symbol));
            
            c2s.put("securityList", new Object[]{security});
            c2s.put("subTypeList", new int[]{subType});
            c2s.put("isSubOrUnSub", true); // true为订阅，false为取消订阅
            c2s.put("isRegOrUnRegPush", true); // 注册推送
            
            request.put("c2s", c2s);
            
            String json = gson.toJson(request);
            log.debug("订阅请求JSON: {}", json);
            
            return json.getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("构建订阅请求失败", e);
            return new byte[0];
        }
    }
    
    /**
     * 构建取消订阅请求
     */
    public byte[] buildUnsubscribeRequest(String symbol, int subType) {
        try {
            Map<String, Object> request = new HashMap<>();
            Map<String, Object> c2s = new HashMap<>();
            
            Map<String, Object> security = new HashMap<>();
            security.put("market", getMarketCode(symbol));
            security.put("code", getStockCode(symbol));
            
            c2s.put("securityList", new Object[]{security});
            c2s.put("subTypeList", new int[]{subType});
            c2s.put("isSubOrUnSub", false); // false为取消订阅
            
            request.put("c2s", c2s);
            
            String json = gson.toJson(request);
            log.debug("取消订阅请求JSON: {}", json);
            
            return json.getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("构建取消订阅请求失败", e);
            return new byte[0];
        }
    }
    
    /**
     * 构建获取K线请求
     */
    public byte[] buildGetKLineRequest(String symbol, int klType, int reqNum, String beginTime, String endTime) {
        try {
            Map<String, Object> request = new HashMap<>();
            Map<String, Object> c2s = new HashMap<>();
            
            Map<String, Object> security = new HashMap<>();
            security.put("market", getMarketCode(symbol));
            security.put("code", getStockCode(symbol));
            
            c2s.put("security", security);
            c2s.put("klType", klType);
            c2s.put("reqNum", reqNum);
            c2s.put("rehabType", 1); // 前复权
            
            if (beginTime != null) {
                c2s.put("beginTime", beginTime);
            }
            if (endTime != null) {
                c2s.put("endTime", endTime);
            }
            
            request.put("c2s", c2s);
            
            String json = gson.toJson(request);
            log.debug("获取K线请求JSON: {}", json);
            
            return json.getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("构建K线请求失败", e);
            return new byte[0];
        }
    }
    
    /**
     * 构建下单请求
     */
    public byte[] buildPlaceOrderRequest(String symbol, int trdSide, int orderType, 
                                        double price, long quantity, String accountId) {
        try {
            Map<String, Object> request = new HashMap<>();
            Map<String, Object> c2s = new HashMap<>();
            Map<String, Object> packetID = new HashMap<>();
            Map<String, Object> header = new HashMap<>();
            
            // 包ID
            packetID.put("connID", "1");
            packetID.put("serialNo", System.currentTimeMillis());
            
            // 交易头
            header.put("trdEnv", 0); // 0:模拟，1:真实
            header.put("accID", accountId);
            header.put("trdMarket", getMarketCode(symbol));
            
            // 订单信息
            c2s.put("packetID", packetID);
            c2s.put("header", header);
            c2s.put("trdSide", trdSide);
            c2s.put("orderType", orderType);
            c2s.put("code", getStockCode(symbol));
            c2s.put("qty", quantity);
            
            if (orderType != 2) { // 非市价单需要价格
                c2s.put("price", price);
            }
            
            request.put("c2s", c2s);
            
            String json = gson.toJson(request);
            log.debug("下单请求JSON: {}", json);
            
            return json.getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("构建下单请求失败", e);
            return new byte[0];
        }
    }
    
    /**
     * 解析响应数据（简化版）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseResponse(byte[] data) {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            return gson.fromJson(json, Map.class);
        } catch (Exception e) {
            log.error("解析响应失败", e);
            return new HashMap<>();
        }
    }
    
    /**
     * 从股票代码获取市场代码
     */
    private int getMarketCode(String symbol) {
        if (symbol.endsWith(".HK")) {
            return 1; // 香港市场
        } else if (symbol.endsWith(".US")) {
            return 11; // 美国市场
        } else if (symbol.endsWith(".SH")) {
            return 21; // 上海市场
        } else if (symbol.endsWith(".SZ")) {
            return 22; // 深圳市场
        }
        return 1; // 默认香港市场
    }
    
    /**
     * 获取纯股票代码（去除市场后缀）
     */
    private String getStockCode(String symbol) {
        int dotIndex = symbol.lastIndexOf('.');
        if (dotIndex > 0) {
            return symbol.substring(0, dotIndex);
        }
        return symbol;
    }
    
    /**
     * 格式化时间为FUTU API格式
     */
    public String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    /**
     * 解析FUTU API时间格式
     */
    public LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr, 
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            log.warn("解析时间失败: {}", dateTimeStr);
            return null;
        }
    }
}