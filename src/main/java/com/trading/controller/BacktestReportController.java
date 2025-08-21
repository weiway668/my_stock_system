package com.trading.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.backtest.PortfolioManager;
import com.trading.domain.entity.BacktestResultEntity;
import com.trading.domain.entity.Order;
import com.trading.repository.BacktestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
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
    private final ObjectMapper objectMapper;

    /**
     * 根据回测ID获取并展示可视化的HTML报告。
     *
     * @param id    回测结果的UUID
     * @param model Spring MVC模型，用于向视图传递数据
     * @return Thymeleaf模板的名称
     */
    @GetMapping("/{id}")
    public String getBacktestReport(@PathVariable String id, Model model) {
        Optional<BacktestResultEntity> resultOptional = backtestResultRepository.findById(id);

        if (resultOptional.isEmpty()) {
            log.warn("未找到ID为 {} 的回测报告", id);
            model.addAttribute("error_message", "未找到ID为 " + id + " 的回测报告");
            return "error/404"; // 返回一个404错误页面
        }

        BacktestResultEntity result = resultOptional.get();
        model.addAttribute("report", result);

        try {
            // 反序列化图表所需的数据
            List<PortfolioManager.EquitySnapshot> equitySnapshots = objectMapper.readValue(
                    result.getDailyEquityChartData(),
                    new TypeReference<>() {}
            );

            List<Order> tradeHistory = objectMapper.readValue(
                    result.getTradeHistoryData(),
                    new TypeReference<>() {}
            );

            // 准备权益曲线图数据
            prepareEquityChartData(model, equitySnapshots);

            // TODO: 准备其他图表的数据 (买卖点、月度收益等)

        } catch (IOException e) {
            log.error("解析图表JSON数据失败, ID: {}", id, e);
            model.addAttribute("error_message", "解析图表数据时出错，报告可能不完整。");
        }

        return "backtest-report"; // 对应 src/main/resources/templates/backtest-report.html
    }

    /**
     * 准备权益曲线图所需的数据并添加到模型中。
     *
     * @param model           Spring MVC模型
     * @param equitySnapshots 每日权益快照列表
     */
    private void prepareEquityChartData(Model model, List<PortfolioManager.EquitySnapshot> equitySnapshots) {
        List<String> equityDates = equitySnapshots.stream()
                .map(snapshot -> snapshot.date().toString())
                .collect(Collectors.toList());

        List<Double> equityValues = equitySnapshots.stream()
                .map(snapshot -> snapshot.totalValue().doubleValue())
                .collect(Collectors.toList());

        model.addAttribute("equityDates", equityDates);
        model.addAttribute("equityValues", equityValues);
    }
}
