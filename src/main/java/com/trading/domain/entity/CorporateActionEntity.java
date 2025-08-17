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
        @Enumerated(EnumType.ORDINAL)
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
        RIGHTS_ISSUE,
        /**
         * 增发
         */
        ADD_ISSUE,
        /**
         * 转赠
         */
        TRANSFER
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CorporateAction(stockCode=").append(stockCode)
          .append(", exDate=").append(exDividendDate)
          .append(", action=");

        switch (actionType) {
            case DIVIDEND:
                sb.append("派息: 每股 ").append(dividend);
                if (spDividend != null && spDividend > 0) {
                    sb.append(", 特别股息: ").append(spDividend);
                }
                break;
            case SPLIT:
                sb.append("拆股: ").append(splitBase).append(" 拆为 ").append(splitErt);
                break;
            case MERGE:
                sb.append("合股: ").append(joinBase).append(" 合为 ").append(joinErt);
                break;
            case BONUS:
                sb.append("送股: 每 ").append(bonusBase).append(" 股送 ").append(bonusErt).append(" 股");
                break;
            case TRANSFER:
                sb.append("转赠: 每 ").append(transferBase).append(" 股转赠 ").append(transferErt).append(" 股");
                break;
            case RIGHTS_ISSUE:
                sb.append("配股: 每 ").append(allotBase).append(" 股配 ").append(allotErt).append(" 股，价格: ").append(allotPrice);
                break;
            case ADD_ISSUE:
                sb.append("增发: 每 ").append(addBase).append(" 股增发 ").append(addErt).append(" 股，价格: ").append(addPrice);
                break;
            case NONE:
                sb.append("无明确行动类型");
                break;
        }

        sb.append(", fwdAdjFactor=").append(forwardAdjFactor)
          .append(", bwdAdjFactor=").append(backwardAdjFactor);
        sb.append(")");
        return sb.toString();
    }
}
