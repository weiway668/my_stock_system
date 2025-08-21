package com.trading.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.backtest.PortfolioManager;
import com.trading.domain.entity.BacktestResultEntity;
import com.trading.domain.entity.MarketData;
import com.trading.domain.entity.Order;
import com.trading.domain.enums.OrderSide;
import com.trading.repository.BacktestResultRepository;
import com.trading.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * 回测报告控制器
 * <p>
 * 负责处理和展示HTML格式的回测报告。
 * </p>
 */
@Slf4j
@Controller
@RequestMapping("/reports/backtest")
@RequiredArgsConstructor
public class BacktestReportController {

    private final BacktestResultRepository backtestResultRepository;
    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public String getBacktestReport(@PathVariable String id, Model model) {
        Optional<BacktestResultEntity> resultOptional = backtestResultRepository.findById(id);

        if (resultOptional.isEmpty()) {
            log.warn("未找到ID为 {} 的回测报告", id);
            model.addAttribute("error_message", "未找到ID为 " + id + " 的回测报告");
            return "error/404";
        }

        BacktestResultEntity result = resultOptional.get();
        model.addAttribute("report", result);

        try {
            List<PortfolioManager.EquitySnapshot> equitySnapshots = objectMapper.readValue(result.getDailyEquityChartData(), new TypeReference<>() {});
            List<Order> tradeHistory = objectMapper.readValue(result.getTradeHistoryData(), new TypeReference<>() {});

            prepareEquityChartData(model, equitySnapshots);
            prepareCandlestickChartData(model, result, tradeHistory);

        } catch (IOException | ExecutionException | InterruptedException e) {
            log.error("准备报告图表数据时出错, ID: {}", id, e);
            model.addAttribute("error_message", "准备报告图表数据时出错，报告可能不完整。");
        }

        return "backtest-report";
    }

    private void prepareEquityChartData(Model model, List<PortfolioManager.EquitySnapshot> equitySnapshots) {
        model.addAttribute("equityDates", equitySnapshots.stream()
                .map(snapshot -> snapshot.date().toString())
                .collect(Collectors.toList()));
        model.addAttribute("equityValues", equitySnapshots.stream()
                .map(snapshot -> snapshot.totalValue().doubleValue())
                .collect(Collectors.toList()));
    }

    private void prepareCandlestickChartData(Model model, BacktestResultEntity result, List<Order> tradeHistory) throws ExecutionException, InterruptedException {
        log.debug("开始为K线图准备数据, symbol={}, from={}, to={}", result.getSymbol(), result.getStartDate(), result.getEndDate());

        // 获取K线数据
        List<MarketData> klineData = marketDataService.getOhlcvData(
                result.getSymbol(),
                "1d", // 假设报告是基于日线
                result.getStartDate().atStartOfDay(),
                result.getEndDate().atStartOfDay(),
                Integer.MAX_VALUE
        ).get();

        log.debug("从MarketDataService获取了 {} 条K线数据", klineData.size());

        if (klineData.isEmpty()) {
            log.warn("K线数据为空，无法生成价格图表");
            model.addAttribute("klineDataEmpty", true);
            return;
        }

        // 准备K线图数据
        List<String> klineDates = klineData.stream()
                .map(data -> data.getTimestamp().toLocalDate().toString())
                .collect(Collectors.toList());

        List<Object[]> ohlcValues = klineData.stream()
                .map(data -> new Object[]{data.getOpen().doubleValue(), data.getClose().doubleValue(), data.getLow().doubleValue(), data.getHigh().doubleValue()})
                .collect(Collectors.toList());

        // 准备买卖点标记
        List<Object[]> buyPoints = tradeHistory.stream()
                .filter(order -> order.getSide() == OrderSide.BUY)
                .map(order -> new Object[]{
                        order.getCreateTime().toLocalDate().toString(),
                        order.getExecutedPrice() != null ? order.getExecutedPrice().doubleValue() : order.getPrice().doubleValue()
                })
                .collect(Collectors.toList());

        List<Object[]> sellPoints = tradeHistory.stream()
                .filter(order -> order.getSide() == OrderSide.SELL)
                .map(order -> new Object[]{
                        order.getCreateTime().toLocalDate().toString(),
                        order.getExecutedPrice() != null ? order.getExecutedPrice().doubleValue() : order.getPrice().doubleValue()
                })
                .collect(Collectors.toList());
        
        log.debug("处理了 {} 个买入点和 {} 个卖出点", buyPoints.size(), sellPoints.size());

        model.addAttribute("klineDates", klineDates);
        model.addAttribute("ohlcValues", ohlcValues);
        model.addAttribute("buyPoints", buyPoints);
        model.addAttribute("sellPoints", sellPoints);
        model.addAttribute("klineDataEmpty", false);
        log.info("K线图数据准备完成，{} 条K线，{} 个买点，{} 个卖点", klineDates.size(), buyPoints.size(), sellPoints.size());
    }
}
