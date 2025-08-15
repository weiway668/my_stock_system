package com.trading.common.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.trading.common.enums.MarketType;
import com.trading.infrastructure.futu.FutuMarketDataService;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 市场交易时间工具类
 * <p>
 * 负责处理不同市场的交易时间、午休、周末等规则，
 * 以便精确计算下一根K线的预期时间。
 * <p>
 * 注意：当前版本暂未实现各市场的公共节假日处理。
 */
@Component
@RequiredArgsConstructor
public class MarketHoursUtil {

    private final FutuMarketDataService futuMarketDataService;
    private final NavigableMap<MarketType, List<TradingSession>> marketSessions = new TreeMap<>();

    @PostConstruct
    public void init() {
        for (MarketType market : MarketType.values()) {
            if (market.getTradingHours() != null && !market.getTradingHours().isEmpty()) {
                marketSessions.put(market, parseSessions(market.getTradingHours()));
            }
        }
    }

    /**
     * 计算下一个交易K线的预期时间戳
     *
     * @param currentKLineTime 当前K线的时间
     * @param klineType        K线周期
     * @param market           市场类型
     * @return 下一个交易K线的预期时间
     */
    public LocalDateTime getNextExpectedTimestamp(LocalDateTime currentKLineTime, KLineType klineType, MarketType market) {
        long intervalMinutes = getIntervalInMinutes(klineType);
        if (intervalMinutes == 0) {
            // 对于周线、月线等，逻辑简化为增加对应天数，不考虑精确交易时间
            return switch (klineType) {
                case K_WEEK -> currentKLineTime.plusWeeks(1);
                case K_MONTH -> currentKLineTime.plusMonths(1);
                default -> currentKLineTime.plusDays(1); // 日线默认加一天
            };
        }

        LocalDateTime nextTime = currentKLineTime.plusMinutes(intervalMinutes);
        return adjustForNonTradingHours(nextTime, market);
    }

    /**
     * 将时间调整到下一个有效的交易时间内
     *
     * @param time   需要调整的时间
     * @param market 市场类型
     * @return 调整后的有效交易时间
     */
    public LocalDateTime adjustForNonTradingHours(LocalDateTime time, MarketType market) {
        List<TradingSession> sessions = marketSessions.get(market);
        if (sessions == null || sessions.isEmpty()) {
            return time; // 如果市场没有定义交易时间，则直接返回
        }

        // 获取当年的交易日历
        Set<String> tradingDays = futuMarketDataService.getTradingDays(market, time.toLocalDate().withDayOfYear(1), time.toLocalDate().withDayOfYear(time.toLocalDate().lengthOfYear()));

        // 循环处理，直到时间落在有效交易日和交易时段内
        while (true) {
            LocalDate currentDate = time.toLocalDate();

            // 1. 调整到交易日 (跳过周末和非交易日)
            if (tradingDays != null && !tradingDays.isEmpty()) {
                // 优先使用API获取的交易日进行判断
                while (!tradingDays.contains(currentDate.toString())) {
                    time = time.toLocalDate().plusDays(1).atTime(sessions.get(0).getStart());
                    currentDate = time.toLocalDate();
                    // 如果跨年，需要重新获取下一年的交易日
                    if (currentDate.getYear() != time.getYear()) {
                        tradingDays = futuMarketDataService.getTradingDays(market, currentDate.withDayOfYear(1), currentDate.withDayOfYear(currentDate.lengthOfYear()));
                    }
                }
            } else {
                // API获取失败时的降级策略：只判断周末
                DayOfWeek dayOfWeek = time.getDayOfWeek();
                if (dayOfWeek == DayOfWeek.SATURDAY) {
                    time = time.toLocalDate().plusDays(2).atTime(sessions.get(0).getStart());
                    continue;
                }
                if (dayOfWeek == DayOfWeek.SUNDAY) {
                    time = time.toLocalDate().plusDays(1).atTime(sessions.get(0).getStart());
                    continue;
                }
            }

            // 2. 检查并调整交易时段
            LocalTime localTime = time.toLocalTime();
            boolean inSession = false;
            for (TradingSession session : sessions) {
                if (!localTime.isBefore(session.getStart()) && localTime.isBefore(session.getEnd())) {
                    inSession = true;
                    break;
                }
            }

            if (inSession) {
                return time; // 时间有效，返回
            }

            // 3. 如果不在任何时段内，则跳到下一个时段或下一天
            LocalTime nextSessionStart = null;
            for (TradingSession session : sessions) {
                if (localTime.isBefore(session.getStart())) {
                    nextSessionStart = session.getStart();
                    break;
                }
            }

            if (nextSessionStart != null) {
                // 跳到当天下一个交易时段的开始
                time = time.toLocalDate().atTime(nextSessionStart);
            } else {
                // 当天所有交易时段已过，跳到下一天的第一个交易时段开始
                time = time.toLocalDate().plusDays(1).atTime(sessions.get(0).getStart());
            }
        }
    }

    private List<TradingSession> parseSessions(String tradingHours) {
        List<TradingSession> sessions = new ArrayList<>();
        String[] parts = tradingHours.split(",");
        for (String part : parts) {
            String[] times = part.split("-");
            sessions.add(new TradingSession(LocalTime.parse(times[0]), LocalTime.parse(times[1])));
        }
        return sessions;
    }

    private long getIntervalInMinutes(KLineType klineType) {
        return switch (klineType) {
            case K_1MIN -> 1;
            case K_5MIN -> 5;
            case K_15MIN -> 15;
            case K_30MIN -> 30;
            case K_60MIN -> 60;
            default -> 0; // 日、周、月线等特殊处理
        };
    }

    /**
     * 交易时段内部类
     */
    @lombok.Getter
    @lombok.AllArgsConstructor
    private static class TradingSession {
        private LocalTime start;
        private LocalTime end;
    }
}
