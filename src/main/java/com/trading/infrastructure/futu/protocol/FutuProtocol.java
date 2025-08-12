package com.trading.infrastructure.futu.protocol;

/**
 * FUTU OpenD协议定义
 * 定义与FUTU OpenD通信的协议常量和消息格式
 */
public class FutuProtocol {
    
    // 协议版本
    public static final int PROTOCOL_VERSION = 1;
    
    // 协议ID定义 - 系统协议
    public static final int PROTO_ID_INIT_CONNECT = 1001;      // 初始化连接
    public static final int PROTO_ID_GET_GLOBAL_STATE = 1002;  // 获取全局状态
    public static final int PROTO_ID_HEARTBEAT = 1004;         // 心跳
    public static final int PROTO_ID_KEEP_ALIVE = 1005;        // 保持连接
    
    // 协议ID定义 - 行情协议
    public static final int PROTO_ID_SUB = 3001;               // 订阅
    public static final int PROTO_ID_UNSUB = 3002;             // 取消订阅
    public static final int PROTO_ID_GET_SUB_INFO = 3003;      // 获取订阅信息
    public static final int PROTO_ID_GET_BASIC_QOT = 3004;     // 获取基本行情
    public static final int PROTO_ID_GET_KL = 3006;            // 获取K线
    public static final int PROTO_ID_GET_RT_DATA = 3008;       // 获取分时数据
    public static final int PROTO_ID_GET_TICKER = 3010;        // 获取逐笔
    public static final int PROTO_ID_GET_ORDER_BOOK = 3012;    // 获取买卖盘
    public static final int PROTO_ID_GET_BROKER_QUEUE = 3014;  // 获取经纪队列
    
    // 协议ID定义 - 推送协议
    public static final int PROTO_ID_PUSH_BASIC_QOT = 3005;    // 推送基本行情
    public static final int PROTO_ID_PUSH_KL = 3007;           // 推送K线
    public static final int PROTO_ID_PUSH_RT = 3009;           // 推送分时
    public static final int PROTO_ID_PUSH_TICKER = 3011;       // 推送逐笔
    public static final int PROTO_ID_PUSH_ORDERBOOK = 3013;    // 推送买卖盘
    public static final int PROTO_ID_PUSH_BROKER = 3015;       // 推送经纪队列
    
    // 协议ID定义 - 交易协议
    public static final int PROTO_ID_GET_ACC_LIST = 2001;      // 获取账户列表
    public static final int PROTO_ID_UNLOCK_TRADE = 2005;      // 解锁交易
    public static final int PROTO_ID_SUB_ACC_PUSH = 2008;      // 订阅账户推送
    public static final int PROTO_ID_PLACE_ORDER = 2202;       // 下单
    public static final int PROTO_ID_MODIFY_ORDER = 2205;      // 修改订单
    public static final int PROTO_ID_CANCEL_ORDER = 2206;      // 取消订单
    public static final int PROTO_ID_GET_ORDER_LIST = 2201;    // 获取订单列表
    public static final int PROTO_ID_GET_POSITION_LIST = 2102; // 获取持仓列表
    public static final int PROTO_ID_GET_MAX_TRD_QTYS = 2111;  // 获取最大可买卖数量
    public static final int PROTO_ID_GET_FUNDS = 2101;         // 获取账户资金
    
    // 推送协议ID
    public static final int PROTO_ID_PUSH_ORDER_UPDATE = 2208; // 订单更新推送
    public static final int PROTO_ID_PUSH_QUOTE_UPDATE = 3005; // 行情更新推送
    public static final int PROTO_ID_PUSH_KLINE_UPDATE = 3007; // K线更新推送
    public static final int PROTO_ID_PUSH_TICKER_UPDATE = 3011;// 逐笔更新推送
    public static final int PROTO_ID_PUSH_ORDER_BOOK_UPDATE = 3013; // 买卖盘更新推送
    
    // 市场类型
    public enum Market {
        UNKNOWN(0, "未知"),
        HK(1, "香港市场"),
        US(11, "美国市场"),
        CN_SH(21, "上海市场"),
        CN_SZ(22, "深圳市场");
        
        private final int code;
        private final String desc;
        
        Market(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        
        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }
    
    // K线类型
    public enum KLType {
        K_1M(1, "1分钟"),
        K_3M(2, "3分钟"),
        K_5M(3, "5分钟"),
        K_15M(4, "15分钟"),
        K_30M(5, "30分钟"),
        K_60M(6, "60分钟"),
        K_DAY(7, "日K"),
        K_WEEK(8, "周K"),
        K_MON(9, "月K"),
        K_QUARTER(10, "季K"),
        K_YEAR(11, "年K");
        
        private final int code;
        private final String desc;
        
        KLType(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        
        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }
    
    // 订阅类型
    public enum SubType {
        QUOTE(1, "基本行情"),
        ORDER_BOOK(2, "买卖盘"),
        TICKER(4, "逐笔"),
        RT_DATA(5, "分时"),
        K_LINE(6, "K线"),
        BROKER(7, "经纪队列");
        
        private final int code;
        private final String desc;
        
        SubType(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        
        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }
    
    // 交易方向
    public enum TrdSide {
        UNKNOWN(0, "未知"),
        BUY(1, "买入"),
        SELL(2, "卖出"),
        SELL_SHORT(3, "卖空"),
        BUY_BACK(4, "买回");
        
        private final int code;
        private final String desc;
        
        TrdSide(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        
        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }
    
    // 订单类型
    public enum OrderType {
        UNKNOWN(0, "未知"),
        NORMAL(1, "普通订单"),
        MARKET(2, "市价单"),
        LIMIT(3, "限价单"),
        ABSOLUTE_LIMIT(4, "绝对限价单"),
        AUCTION(5, "竞价单"),
        AUCTION_LIMIT(6, "竞价限价单"),
        SPECIAL_LIMIT(7, "特别限价单");
        
        private final int code;
        private final String desc;
        
        OrderType(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        
        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }
    
    // 订单状态
    public enum OrderStatus {
        UNKNOWN(0, "未知"),
        SUBMITTED(1, "已提交"),
        WAITING_SUBMIT(2, "等待提交"),
        FILLED_ALL(3, "全部成交"),
        FILLED_PART(4, "部分成交"),
        CANCELLED_ALL(5, "全部取消"),
        CANCELLED_PART(6, "部分取消"),
        FAILED(7, "失败"),
        DISABLED(8, "已失效"),
        DELETED(9, "已删除");
        
        private final int code;
        private final String desc;
        
        OrderStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        
        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }
    
    // 错误码定义
    public static class ErrorCode {
        public static final int SUCCESS = 0;                    // 成功
        public static final int TIMEOUT = -100;                // 超时
        public static final int DISCONNECT = -101;             // 连接断开
        public static final int UNKNOWN_ERROR = -102;          // 未知错误
        public static final int INVALID_PARAM = -103;          // 参数错误
        public static final int RATE_LIMIT = -104;             // 频率限制
        public static final int NOT_INIT = -105;               // 未初始化
        public static final int DECRYPT_FAILED = -106;         // 解密失败
        public static final int PROTOCOL_ERROR = -107;         // 协议错误
    }
    
    // 消息头格式
    public static class MessageHeader {
        public byte[] szHeaderFlag = new byte[2];  // 包头标识 "FT"
        public int nProtoID;                       // 协议ID
        public byte nProtoFmtType;                 // 协议格式类型 0:Protobuf 1:Json
        public byte nProtoVer;                     // 协议版本
        public int nSerialNo;                      // 包序列号
        public int nBodyLen;                       // 包体长度
        public byte[] arrBodySHA1 = new byte[20];  // 包体SHA1
        public byte[] arrReserved = new byte[8];   // 保留字段
        
        public static final int HEADER_SIZE = 44;  // 消息头固定大小
        public static final byte[] HEADER_FLAG = new byte[]{'F', 'T'};
    }
}