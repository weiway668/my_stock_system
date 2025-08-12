package com.trading.infrastructure.futu.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * FUTU API响应对象
 */
@Data
@Builder
public class FutuResponse {
    private int retType;                    // 返回类型 0:成功 其他:失败
    private String retMsg;                  // 返回消息
    private int errCode;                    // 错误码
    private Map<String, Object> data;       // 响应数据
    private int serialNo;                   // 序列号
    
    public FutuResponse() {
    }
    
    public FutuResponse(int retType, String retMsg, int errCode, Map<String, Object> data, int serialNo) {
        this.retType = retType;
        this.retMsg = retMsg;
        this.errCode = errCode;
        this.data = data;
        this.serialNo = serialNo;
    }
    
    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return retType == 0;
    }
}