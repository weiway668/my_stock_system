package com.trading.common.enums;

import com.futu.openapi.pb.QotCommon;
import lombok.Getter;

/**
 * 市场类型枚举
 * 支持港股、A股（沪深）、美股等多个市场
 */
@Getter
public enum MarketType {
    
    /**
     * 港股市场
     */
    HK("港股", ".HK", QotCommon.QotMarket.QotMarket_HK_Security_VALUE, "HKD", "09:30-12:00,13:00-16:00"),
    
    /**
     * A股沪市
     */
    CN_SH("沪市", ".SH", QotCommon.QotMarket.QotMarket_CNSH_Security_VALUE, "CNY", "09:30-11:30,13:00-15:00"),
    
    /**
     * A股深市
     */
    CN_SZ("深市", ".SZ", QotCommon.QotMarket.QotMarket_CNSZ_Security_VALUE, "CNY", "09:30-11:30,13:00-15:00"),
    
    /**
     * 美股市场
     */
    US("美股", ".US", QotCommon.QotMarket.QotMarket_US_Security_VALUE, "USD", "09:30-16:00"),
    
    /**
     * 未知市场
     */
    UNKNOWN("未知", "", QotCommon.QotMarket.QotMarket_Unknown_VALUE, "", "");
    
    /**
     * 市场中文名称
     */
    private final String description;
    
    /**
     * 市场代码后缀
     */
    private final String suffix;
    
    /**
     * FUTU API市场代码
     */
    private final int futuMarketCode;
    
    /**
     * 货币代码
     */
    private final String currency;
    
    /**
     * 交易时间（简化表示）
     */
    private final String tradingHours;
    
    MarketType(String description, String suffix, int futuMarketCode, String currency, String tradingHours) {
        this.description = description;
        this.suffix = suffix;
        this.futuMarketCode = futuMarketCode;
        this.currency = currency;
        this.tradingHours = tradingHours;
    }
    
    /**
     * 根据股票代码自动识别市场类型
     * 
     * @param symbol 股票代码（可能包含市场后缀）
     * @return 市场类型
     */
    public static MarketType fromSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return UNKNOWN;
        }
        
        // 转为大写进行匹配
        String upperSymbol = symbol.toUpperCase();
        
        // 1. 先根据后缀判断
        if (upperSymbol.endsWith(".HK")) {
            return HK;
        }
        if (upperSymbol.endsWith(".SH")) {
            return CN_SH;
        }
        if (upperSymbol.endsWith(".SZ")) {
            return CN_SZ;
        }
        if (upperSymbol.endsWith(".US")) {
            return US;
        }
        
        // 2. 如果没有后缀，根据代码规则判断（仅支持A股）
        // 去除可能的后缀，获取纯代码
        String pureCode = symbol.split("\\.")[0];
        
        // A股代码规则判断
        if (pureCode.length() == 6 && pureCode.matches("\\d{6}")) {
            char firstChar = pureCode.charAt(0);
            
            // 沪市股票代码规则
            if (firstChar == '6') {
                return CN_SH;  // 60开头为沪市主板，68开头为科创板
            }
            
            // 深市股票代码规则
            if (firstChar == '0' || firstChar == '3') {
                return CN_SZ;  // 00开头为深市主板，30开头为创业板
            }
            
            // 沪市其他代码
            if (pureCode.startsWith("900")) {
                return CN_SH;  // 沪市B股
            }
            
            // 深市其他代码
            if (pureCode.startsWith("200")) {
                return CN_SZ;  // 深市B股
            }
        }
        
        // 3. 港股代码规则（5位数字）
        if (pureCode.length() == 5 && pureCode.matches("\\d{5}")) {
            return HK;
        }
        
        // 4. 美股代码规则（1-5位字母）
        if (pureCode.matches("[A-Z]{1,5}")) {
            return US;
        }
        
        return UNKNOWN;
    }
    
    /**
     * 根据FUTU市场代码获取市场类型
     * 
     * @param futuMarketCode FUTU API市场代码
     * @return 市场类型
     */
    public static MarketType fromFutuMarketCode(int futuMarketCode) {
        for (MarketType market : values()) {
            if (market.futuMarketCode == futuMarketCode) {
                return market;
            }
        }
        return UNKNOWN;
    }
    
    /**
     * 获取不带后缀的股票代码
     * 
     * @param symbol 原始股票代码
     * @return 不带后缀的代码
     */
    public static String getPureCode(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return symbol;
        }
        
        // 移除所有已知的市场后缀
        for (MarketType market : values()) {
            if (!market.suffix.isEmpty() && symbol.toUpperCase().endsWith(market.suffix)) {
                return symbol.substring(0, symbol.length() - market.suffix.length());
            }
        }
        
        return symbol;
    }
    
    /**
     * 为股票代码添加市场后缀
     * 
     * @param pureCode 纯股票代码
     * @param market 市场类型
     * @return 带后缀的股票代码
     */
    public static String addMarketSuffix(String pureCode, MarketType market) {
        if (pureCode == null || market == null || market == UNKNOWN) {
            return pureCode;
        }
        
        // 如果已经有后缀，先移除
        String code = getPureCode(pureCode);
        
        // 添加新的市场后缀
        return code + market.suffix;
    }
    
    /**
     * 判断是否为A股市场
     */
    public boolean isAStock() {
        return this == CN_SH || this == CN_SZ;
    }
    
    /**
     * 判断是否为中国市场（包括港股和A股）
     */
    public boolean isChineseMarket() {
        return this == HK || this == CN_SH || this == CN_SZ;
    }
}