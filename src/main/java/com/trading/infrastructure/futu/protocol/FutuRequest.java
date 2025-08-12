package com.trading.infrastructure.futu.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * FUTU API请求对象
 */
@Data
@Builder
public class FutuRequest {
    private int protoId;                    // 协议ID
    private Map<String, Object> data;       // 请求数据
    private int serialNo;                   // 序列号
    private boolean needReply;              // 是否需要回复
    
    public FutuRequest() {
        this.needReply = true;
    }
    
    public FutuRequest(int protoId, Map<String, Object> data, int serialNo, boolean needReply) {
        this.protoId = protoId;
        this.data = data;
        this.serialNo = serialNo;
        this.needReply = needReply;
    }
}