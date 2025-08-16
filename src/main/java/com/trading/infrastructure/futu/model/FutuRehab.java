package com.trading.infrastructure.futu.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 用于接收富途API返回的复权因子数据模型
 */
@Data
public class FutuRehab {

    @JsonProperty("security")
    private Security security;

    @JsonProperty("rehab_list")
    private List<Rehab> rehabList;

    @Data
    public static class Security {
        @JsonProperty("market")
        private int market;

        @JsonProperty("code")
        private String code;
    }

    @Data
    public static class Rehab {
        /**
         * 时间
         */
        @JsonProperty("time")
        private String time;

        /**
         * 公司行动ID
         */
        @JsonProperty("company_act_id")
        private long companyActId;

        /**
         * 前复权因子A
         */
        @JsonProperty("fwd_factor_a")
        private double fwdFactorA;

        /**
         * 前复权因子B
         */
        @JsonProperty("fwd_factor_b")
        private double fwdFactorB;

        /**
         * 后复权因子A
         */
        @JsonProperty("bwd_factor_a")
        private double bwdFactorA;

        /**
         * 后复权因子B
         */
        @JsonProperty("bwd_factor_b")
        private double bwdFactorB;

        /**
         * 拆股、合股、送股、配股时，该字段有效
         */
        @JsonProperty("split_base")
        private int splitBase;

        @JsonProperty("split_ert")
        private int splitErt;

        @JsonProperty("join_base")
        private int joinBase;

        @JsonProperty("join_ert")
        private int joinErt;

        @JsonProperty("bonus_base")
        private int bonusBase;

        @JsonProperty("bonus_ert")
        private int bonusErt;

        @JsonProperty("transfer_base")
        private int transferBase;

        @JsonProperty("transfer_ert")
        private int transferErt;

        @JsonProperty("allot_base")
        private int allotBase;

        @JsonProperty("allot_ert")
        private int allotErt;

        @JsonProperty("allot_price")
        private double allotPrice;

        @JsonProperty("add_base")
        private int addBase;

        @JsonProperty("add_ert")
        private int addErt;

        @JsonProperty("add_price")
        private double addPrice;

        /**
         * 派息时，该字段有效
         */
        @JsonProperty("dividend")
        private double dividend;

        @JsonProperty("sp_dividend")
        private double spDividend;
    }
}
