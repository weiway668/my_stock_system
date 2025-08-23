package com.trading.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.backtest.PortfolioManager;
import com.trading.domain.entity.BacktestResultEntity;
import com.trading.domain.entity.Order;
import com.trading.repository.BacktestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/reports/backtest")
@RequiredArgsConstructor
public class BacktestReportController {

    private final BacktestResultRepository backtestResultRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public String getReport(@PathVariable UUID id, Model model) {
        BacktestResultEntity result = backtestResultRepository.findById(id.toString())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest report not found"));

        prepareViewModel(result, model);
        prepareChartAndTableData(result, model);

        return "backtest-report";
    }

    private void prepareViewModel(BacktestResultEntity result, Model model) {
        String currencySymbol = "HK$";
        model.addAttribute("currencySymbol", currencySymbol);
        model.addAttribute("result", result);

        // --- 收益指标 ---
        model.addAttribute("initialCapitalStr", formatCurrency(result.getInitialCapital(), currencySymbol));
        model.addAttribute("finalCapitalStr", formatCurrency(result.getFinalCapital(), currencySymbol));
        BigDecimal totalReturn = result.getTotalReturn();
        // 向后兼容：如果是旧的数据库记录，totalReturn字段可能为null，此时动态计算
        if (totalReturn == null && result.getFinalCapital() != null && result.getInitialCapital() != null) {
            totalReturn = result.getFinalCapital().subtract(result.getInitialCapital());
        }
        model.addAttribute("totalReturnStr", formatCurrency(totalReturn, currencySymbol));
        model.addAttribute("isTotalReturnPositive", totalReturn != null && totalReturn.compareTo(BigDecimal.ZERO) >= 0);
        BigDecimal annualizedReturn = result.getAnnualizedReturn();
        model.addAttribute("annualizedReturnStr", formatPercentage(annualizedReturn, 2));
        model.addAttribute("isAnnualizedReturnPositive", annualizedReturn != null && annualizedReturn.compareTo(BigDecimal.ZERO) >= 0);

        // --- 风险指标 ---
        model.addAttribute("maxDrawdownStr", formatPercentage(result.getMaxDrawdown(), 2));
        model.addAttribute("sharpeRatioStr", formatDecimal(result.getSharpeRatio(), 2));
        model.addAttribute("sortinoRatioStr", formatDecimal(result.getSortinoRatio(), 2));
        model.addAttribute("calmarRatioStr", formatDecimal(result.getCalmarRatio(), 2));

        // --- 交易统计 ---
        model.addAttribute("winRateStr", formatPercentage(result.getWinRate(), 1));
        model.addAttribute("profitLossRatioStr", formatDecimal(result.getProfitLossRatio(), 2));

        // --- 成本分析 ---
        model.addAttribute("totalCostStr", formatCurrency(result.getTotalCost(), currencySymbol));
        model.addAttribute("totalCommissionStr", formatCurrency(result.getTotalCommission(), currencySymbol));
        model.addAttribute("totalStampDutyStr", formatCurrency(result.getTotalStampDuty(), currencySymbol));
        model.addAttribute("totalPlatformFeeStr", formatCurrency(result.getTotalPlatformFee(), currencySymbol));
        String costToReturnRatioStr = "N/A";
        if (totalReturn != null && totalReturn.compareTo(BigDecimal.ZERO) > 0 && result.getTotalCost() != null) {
            BigDecimal ratio = result.getTotalCost().divide(totalReturn, 4, RoundingMode.HALF_UP);
            costToReturnRatioStr = formatPercentage(ratio, 2);
        }
        model.addAttribute("costToReturnRatioStr", costToReturnRatioStr);
    }

    private void prepareChartAndTableData(BacktestResultEntity result, Model model) {
        List<PortfolioManager.EquitySnapshot> equitySnapshots = new ArrayList<>();
        List<Order> tradeHistory = new ArrayList<>();
        try {
            if (result.getDailyEquityChartData() != null && !result.getDailyEquityChartData().isBlank()) {
                equitySnapshots = objectMapper.readValue(result.getDailyEquityChartData(), new TypeReference<>() {});
            }
            if (result.getTradeHistoryData() != null && !result.getTradeHistoryData().isBlank()) {
                tradeHistory = objectMapper.readValue(result.getTradeHistoryData(), new TypeReference<>() {});
            }
        } catch (JsonProcessingException e) {
            log.error("解析报告JSON数据时出错, ID: {}. 图表和表格可能为空.", result.getId(), e);
        }
        model.addAttribute("equitySnapshots", equitySnapshots);
        model.addAttribute("tradeHistory", tradeHistory);
    }

    private String formatCurrency(BigDecimal value, String symbol) {
        if (value == null) return symbol + "0.00";
        return symbol + new DecimalFormat("#,##0.00").format(value);
    }

    private String formatDecimal(BigDecimal value, int fractionDigits) {
        if (value == null) return "0.00";
        DecimalFormat df = new DecimalFormat();
        df.setMaximumFractionDigits(fractionDigits);
        df.setMinimumFractionDigits(fractionDigits);
        df.setGroupingUsed(false);
        return df.format(value);
    }

    private String formatPercentage(BigDecimal value, int fractionDigits) {
        if (value == null) return "0." + "0".repeat(fractionDigits) + "%";
        return formatDecimal(value.multiply(BigDecimal.valueOf(100)), fractionDigits) + "%";
    }
}
