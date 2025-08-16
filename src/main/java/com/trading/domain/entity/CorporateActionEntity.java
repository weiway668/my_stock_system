package com.trading.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 公司行动实体类，用于存储复权因子等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "corporate_action", indexes = {
        @Index(name = "idx_stock_code", columnList = "stockCode")
})
public class CorporateActionEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 股票代码，例如 "HK.00700"
     */
    @Column(nullable = false)
    private String stockCode;

    /**
     * 除权除息日
     */
    @Column(nullable = false)
    private LocalDate exDividendDate;

    /**
     * 公司行动类型
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CorporateActionType actionType;

    /**
     * 前复权因子
     * 计算公式：前复权价格 = 原始价格 * forwardAdjFactor
     */
    @Column(nullable = false)
    private Double forwardAdjFactor;

    /**
     * 后复权因子
     * 计算公式：后复权价格 = 原始价格 * backwardAdjFactor
     */
    @Column(nullable = false)
    private Double backwardAdjFactor;

    // --- 以下是具体行动的详细信息 ---

    /**
     * 每股派息金额
     */
    private Double dividend;

    /**
     * 每股特别股息
     */
    private Double spDividend;

    /**
     * 拆股，每 splitBase 股拆为 splitErt 股
     */
    private Double splitBase;
    private Double splitErt;

    /**
     * 合股，每 joinBase 股合为 joinErt 股
     */
    private Double joinBase;
    private Double joinErt;

    /**
     * 送股，每 bonusBase 股送 bonusErt 股
     */
    private Double bonusBase;
    private Double bonusErt;

    /**
     * 转赠股，每 transferBase 股转增 transferErt 股
     */
    private Double transferBase;
    private Double transferErt;

    /**
     * 配股，每 allotBase 股配 allotErt 股
     */
    private Double allotBase;
    private Double allotErt;

    /**
     * 配股价
     */
    private Double allotPrice;

    /**
     * 增发，每 addBase 股增发 addErt 股
     */
    private Double addBase;
    private Double addErt;

    /**
     * 增发价
     */
    private Double addPrice;


    public enum CorporateActionType {
        /**
         * 无
         */
        NONE,
        /**
         * 派息
         */
        DIVIDEND,
        /**
         * 拆股
         */
        SPLIT,
        /**
         * 合股
         */
        MERGE,
        /**
         * 送股
         */
        BONUS,
        /**
         * 配股
         */
        RIGHTS_ISSUE
    }
}
