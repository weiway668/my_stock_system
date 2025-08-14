package com.trading.infrastructure.futu;

import com.futu.openapi.pb.*;
import com.trading.infrastructure.futu.model.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * FUTU数据转换工具类
 * 负责在FUTU原生数据格式和我们的数据模型之间进行转换
 */
@Slf4j
public class FutuDataConverter {

    /**
     * 转换FUTU基础报价到FutuQuote模型
     */
    public static FutuQuote convertBasicQuote(QotCommon.BasicQot basicQot) {
        if (basicQot == null) {
            return null;
        }

        try {
            return FutuQuote.builder()
                .code(basicQot.getSecurity().getCode())
                .name(basicQot.hasName() ? basicQot.getName() : "")
                .lastPrice(BigDecimal.valueOf(basicQot.getCurPrice()))
                .openPrice(BigDecimal.valueOf(basicQot.getOpenPrice()))
                .highPrice(BigDecimal.valueOf(basicQot.getHighPrice()))
                .lowPrice(BigDecimal.valueOf(basicQot.getLowPrice()))
                .preClosePrice(BigDecimal.valueOf(basicQot.getLastClosePrice()))
                .volume(basicQot.getVolume())
                .turnover(BigDecimal.valueOf(basicQot.getTurnover()))
                .changeValue(BigDecimal.valueOf(basicQot.getCurPrice() - basicQot.getLastClosePrice()))
                .changeRate(calculateChangeRate(basicQot.getCurPrice(), basicQot.getLastClosePrice()))
                .timestamp(LocalDateTime.now())
                .status(FutuQuote.DataStatus.NORMAL)
                .build();

        } catch (Exception e) {
            log.error("转换基础报价数据异常", e);
            return null;
        }
    }

    /**
     * 转换FUTU订单簿到FutuOrderBook模型
     */
    public static FutuOrderBook convertOrderBook(String symbol, QotUpdateOrderBook.S2C orderBookData) {
        if (orderBookData == null) {
            return null;
        }

        try {
            List<FutuOrderBook.OrderBookEntry> bidList = new ArrayList<>();
            List<FutuOrderBook.OrderBookEntry> askList = new ArrayList<>();

            // 注意：这里需要根据实际FUTU SDK的OrderBook结构来调整
            // 由于FUTU API文档可能有变化，我们先提供基础框架
            
            // 处理买盘数据 - 需要根据实际FUTU SDK的OrderBook结构调整
            // 当前先创建空的列表，实际使用时需要根据SDK文档来提取具体数据
            log.debug("处理订单簿数据: {} (数据结构待确认)", symbol);

            return FutuOrderBook.builder()
                .code(symbol)
                .bidList(bidList)
                .askList(askList)
                .timestamp(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("转换订单簿数据异常", e);
            return null;
        }
    }

    /**
     * 转换FUTU K线数据到FutuKLine模型
     */
    public static FutuKLine convertKLine(QotCommon.KLine klineData) {
        if (klineData == null) {
            return null;
        }

        try {
            // TODO: 根据实际FUTU SDK的K线数据结构进行转换
            // 由于当前SDK版本的方法签名可能不匹配，这里提供基础框架
            log.debug("转换K线数据 - 需要根据实际FUTU SDK结构调整");
            
            // 暂时返回基础结构，实际使用时需要根据FUTU SDK文档调整字段访问
            return FutuKLine.builder()
                .code("待实现") // 需要从实际API中提取
                .timestamp(LocalDateTime.now()) // 需要从实际API中提取时间戳
                .open(BigDecimal.ZERO) // 需要从实际API中提取价格数据
                .high(BigDecimal.ZERO)
                .low(BigDecimal.ZERO) 
                .close(BigDecimal.ZERO)
                .volume(0L) // 需要从实际API中提取成交量
                .turnover(BigDecimal.ZERO)
                .changeValue(BigDecimal.ZERO)
                .changeRate(BigDecimal.ZERO)
                .preClose(BigDecimal.ZERO)
                .kLineType(FutuKLine.KLineType.K_DAY) // 默认日K
                .rehabType(FutuKLine.RehabType.NONE) // 默认不复权
                .build();

        } catch (Exception e) {
            log.error("转换K线数据异常", e);
            return null;
        }
    }

    /**
     * 转换FUTU订单到FutuOrder模型
     */
    public static FutuOrder convertOrder(TrdCommon.Order futuOrderData) {
        if (futuOrderData == null) {
            return null;
        }

        try {
            // TODO: 根据实际FUTU SDK的订单数据结构进行转换
            // 由于当前SDK版本的方法签名可能不匹配，这里提供基础框架
            log.debug("转换订单数据 - 需要根据实际FUTU SDK结构调整");
            
            // 暂时返回基础结构，实际使用时需要根据FUTU SDK文档调整字段访问
            return FutuOrder.builder()
                .orderId("待实现") // 需要从实际API中提取
                .code("待实现") // 需要从实际API中提取
                .name("待实现")
                .trdSide(FutuOrder.TrdSide.UNKNOWN) // 需要根据实际API转换
                .orderType(FutuOrder.OrderType.UNKNOWN) // 需要根据实际API转换
                .orderStatus(FutuOrder.OrderStatus.UNKNOWN) // 需要根据实际API转换
                .price(BigDecimal.ZERO)
                .quantity(0L)
                .fillPrice(BigDecimal.ZERO)
                .fillQuantity(0L)
                .avgPrice(BigDecimal.ZERO)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .remark("模拟订单数据")
                .build();

        } catch (Exception e) {
            log.error("转换订单数据异常", e);
            return null;
        }
    }

    /**
     * 转换时间戳 (字符串格式)
     */
    private static LocalDateTime convertTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return LocalDateTime.now();
        }
        
        try {
            // 尝试不同的时间戳格式
            
            // 1. Unix时间戳秒（字符串）
            if (timestamp.matches("\\d{10}")) {
                long epochSecond = Long.parseLong(timestamp);
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.systemDefault());
            }
            
            // 2. Unix时间戳毫秒（字符串）
            if (timestamp.matches("\\d{13}")) {
                long epochMilli = Long.parseLong(timestamp);
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
            }
            
            // 3. YYYY-MM-DD HH:mm:ss 格式
            if (timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                return LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            
            // 4. ISO 8601 格式
            return LocalDateTime.parse(timestamp);
            
        } catch (Exception e) {
            log.warn("时间戳转换失败: {}, 使用当前时间", timestamp);
            return LocalDateTime.now();
        }
    }
    
    /**
     * 转换时间戳 (数值格式)
     */
    public static LocalDateTime convertTimestamp(long timestamp) {
        try {
            // 判断是秒还是毫秒时间戳
            if (timestamp > 1000000000000L) {
                // 毫秒时间戳
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
            } else {
                // 秒时间戳
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
            }
        } catch (Exception e) {
            log.warn("数值时间戳转换失败: {}, 使用当前时间", timestamp);
            return LocalDateTime.now();
        }
    }

    /**
     * 计算涨跌幅
     */
    private static BigDecimal calculateChangeRate(double currentPrice, double preClosePrice) {
        if (preClosePrice == 0) {
            return BigDecimal.ZERO;
        }
        
        double changeRate = (currentPrice - preClosePrice) / preClosePrice * 100;
        return BigDecimal.valueOf(changeRate);
    }

    /**
     * 转换市场类型
     */
    public static String convertMarketType(int marketType) {
        return switch (marketType) {
            case 1 -> "HK"; // 港股
            case 2 -> "US"; // 美股
            case 3 -> "SH"; // 沪股
            case 4 -> "SZ"; // 深股
            default -> "UNKNOWN";
        };
    }

    /**
     * 转换订单状态
     */
    public static FutuOrder.OrderStatus convertOrderStatus(int futuStatus) {
        return switch (futuStatus) {
            case 0 -> FutuOrder.OrderStatus.UNKNOWN;
            case 1 -> FutuOrder.OrderStatus.SUBMITTED;
            case 2 -> FutuOrder.OrderStatus.WAITING_SUBMIT;
            case 3 -> FutuOrder.OrderStatus.FILLED_ALL;
            case 4 -> FutuOrder.OrderStatus.FILLED_PART;
            case 5 -> FutuOrder.OrderStatus.CANCELLED_ALL;
            case 6 -> FutuOrder.OrderStatus.CANCELLED_PART;
            case 7 -> FutuOrder.OrderStatus.FAILED;
            case 8 -> FutuOrder.OrderStatus.DISABLED;
            case 9 -> FutuOrder.OrderStatus.DELETED;
            default -> FutuOrder.OrderStatus.UNKNOWN;
        };
    }

    /**
     * 转换交易方向
     */
    public static FutuOrder.TrdSide convertTradeSide(int futuSide) {
        return switch (futuSide) {
            case 0 -> FutuOrder.TrdSide.UNKNOWN;
            case 1 -> FutuOrder.TrdSide.BUY;
            case 2 -> FutuOrder.TrdSide.SELL;
            case 3 -> FutuOrder.TrdSide.SELL_SHORT;
            case 4 -> FutuOrder.TrdSide.BUY_BACK;
            default -> FutuOrder.TrdSide.UNKNOWN;
        };
    }

    /**
     * 转换K线类型
     */
    public static FutuKLine.KLineType convertKLineType(int futuType) {
        return switch (futuType) {
            case 1 -> FutuKLine.KLineType.K_1M;
            case 2 -> FutuKLine.KLineType.K_3M;
            case 3 -> FutuKLine.KLineType.K_5M;
            case 4 -> FutuKLine.KLineType.K_15M;
            case 5 -> FutuKLine.KLineType.K_30M;
            case 6 -> FutuKLine.KLineType.K_60M;
            case 7 -> FutuKLine.KLineType.K_DAY;
            case 8 -> FutuKLine.KLineType.K_WEEK;
            case 9 -> FutuKLine.KLineType.K_MON;
            case 10 -> FutuKLine.KLineType.K_QUARTER;
            case 11 -> FutuKLine.KLineType.K_YEAR;
            default -> FutuKLine.KLineType.K_DAY;
        };
    }

    /**
     * 创建订单簿条目
     */
    public static FutuOrderBook.OrderBookEntry createOrderBookEntry(double price, long volume, int orderCount) {
        return FutuOrderBook.OrderBookEntry.builder()
            .price(BigDecimal.valueOf(price))
            .volume(volume)
            .orderCount(orderCount)
            .brokerIds(new ArrayList<>()) // 经纪商ID列表，根据需要填充
            .build();
    }

    /**
     * 安全的BigDecimal转换
     */
    public static BigDecimal safeBigDecimal(double value) {
        try {
            return BigDecimal.valueOf(value);
        } catch (Exception e) {
            log.warn("BigDecimal转换失败: {}, 返回ZERO", value);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 安全的Long转换
     */
    public static Long safeLong(long value) {
        return value >= 0 ? value : 0L;
    }
    
    /**
     * 批量转换K线数据
     */
    public static List<FutuKLine> convertKLineList(List<QotCommon.KLine> klineDataList) {
        if (klineDataList == null || klineDataList.isEmpty()) {
            return new ArrayList<>();
        }
        
        return klineDataList.stream()
            .map(FutuDataConverter::convertKLine)
            .filter(kline -> kline != null) // 过滤转换失败的数据
            .toList();
    }
    
    /**
     * 批量转换报价数据
     */
    public static List<FutuQuote> convertBasicQuoteList(List<QotCommon.BasicQot> quoteDataList) {
        if (quoteDataList == null || quoteDataList.isEmpty()) {
            return new ArrayList<>();
        }
        
        return quoteDataList.stream()
            .map(FutuDataConverter::convertBasicQuote)
            .filter(quote -> quote != null) // 过滤转换失败的数据
            .toList();
    }
    
    /**
     * 批量转换订单数据
     */
    public static List<FutuOrder> convertOrderList(List<TrdCommon.Order> orderDataList) {
        if (orderDataList == null || orderDataList.isEmpty()) {
            return new ArrayList<>();
        }
        
        return orderDataList.stream()
            .map(FutuDataConverter::convertOrder)
            .filter(order -> order != null) // 过滤转换失败的数据
            .toList();
    }
    
    /**
     * 增强的订单簿转换 - 支持完整的买卖盘数据处理
     */
    public static FutuOrderBook convertOrderBookEnhanced(String symbol, QotUpdateOrderBook.S2C orderBookData) {
        if (orderBookData == null) {
            return null;
        }

        try {
            List<FutuOrderBook.OrderBookEntry> bidList = new ArrayList<>();
            List<FutuOrderBook.OrderBookEntry> askList = new ArrayList<>();

            // 处理买盘数据（这里需要根据实际FUTU SDK结构调整）
            // 注意：由于FUTU API结构可能变化，这里提供通用框架
            log.debug("处理增强订单簿数据: {}", symbol);
            
            // TODO: 根据实际FUTU SDK的OrderBook结构来提取买卖盘数据
            // 示例结构（需要根据实际API调整）:
            /*
            if (orderBookData.hasOrderBook()) {
                QotCommon.OrderBook orderBook = orderBookData.getOrderBook();
                
                // 处理买盘
                for (QotCommon.OrderBookEntry entry : orderBook.getBidListList()) {
                    bidList.add(createOrderBookEntry(
                        entry.getPrice(), 
                        entry.getVolume(),
                        entry.getOrderCount()
                    ));
                }
                
                // 处理卖盘
                for (QotCommon.OrderBookEntry entry : orderBook.getAskListList()) {
                    askList.add(createOrderBookEntry(
                        entry.getPrice(),
                        entry.getVolume(),
                        entry.getOrderCount()
                    ));
                }
            }
            */

            return FutuOrderBook.builder()
                .code(symbol)
                .bidList(bidList)
                .askList(askList)
                .timestamp(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("增强订单簿转换异常: symbol={}", symbol, e);
            return null;
        }
    }
    
    /**
     * 验证数据完整性
     */
    public static boolean validateQuoteData(FutuQuote quote) {
        if (quote == null) return false;
        
        return quote.getCode() != null && !quote.getCode().isEmpty() &&
               quote.getLastPrice() != null && quote.getLastPrice().compareTo(BigDecimal.ZERO) > 0 &&
               quote.getTimestamp() != null;
    }
    
    /**
     * 验证K线数据完整性
     */
    public static boolean validateKLineData(FutuKLine kline) {
        if (kline == null) return false;
        
        return kline.getCode() != null && !kline.getCode().isEmpty() &&
               kline.getOpen() != null && kline.getHigh() != null &&
               kline.getLow() != null && kline.getClose() != null &&
               kline.getTimestamp() != null &&
               kline.getHigh().compareTo(kline.getLow()) >= 0; // 最高价应大于等于最低价
    }
    
    /**
     * 计算价格变化百分比
     */
    public static BigDecimal calculatePriceChangePercent(BigDecimal currentPrice, BigDecimal previousPrice) {
        if (currentPrice == null || previousPrice == null || previousPrice.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        
        return currentPrice.subtract(previousPrice)
            .divide(previousPrice, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    }
}