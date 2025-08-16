package com.trading.infrastructure.futu;

import com.futu.openapi.pb.QotCommon;
import com.trading.domain.entity.CorporateActionEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FutuDataConverter 的单元测试类
 */
class FutuDataConverterTest {

    private static final String STOCK_CODE = "HK.00700";

    @Test
    void testConvertToCorporateActionList_DividendOnly() {
        // 1. 准备数据: 只有派息
        QotCommon.Rehab futuRehab = QotCommon.Rehab.newBuilder()
                .setTime("2023-03-23")
                .setCompanyActFlag(1L << 0) // 派息
                .setFwdFactorA(0.99)
                .setBwdFactorA(1.01)
                .setDividend(2.4)
                .build();

        // 2. 执行转换
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(futuRehab, STOCK_CODE);

        // 3. 断言
        assertNotNull(result);
        assertEquals(1, result.size());
        CorporateActionEntity action = result.get(0);
        assertEquals(STOCK_CODE, action.getStockCode());
        assertEquals(CorporateActionEntity.CorporateActionType.DIVIDEND, action.getActionType());
        assertEquals(LocalDate.parse("2023-03-23"), action.getExDividendDate());
        assertEquals(2.4, action.getDividend());
        assertEquals(0.99, action.getForwardAdjFactor());
    }

    @Test
    void testConvertToCorporateActionList_SplitOnly() {
        // 1. 准备数据: 只有拆股
        QotCommon.Rehab futuRehab = QotCommon.Rehab.newBuilder()
                .setTime("2014-05-15")
                .setCompanyActFlag(1L << 1) // 拆股
                .setFwdFactorA(0.2)
                .setBwdFactorA(5.0)
                .setSplitBase(1)
                .setSplitErt(5)
                .build();

        // 2. 执行转换
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(futuRehab, STOCK_CODE);

        // 3. 断言
        assertEquals(1, result.size());
        CorporateActionEntity action = result.get(0);
        assertEquals(CorporateActionEntity.CorporateActionType.SPLIT, action.getActionType());
        assertEquals(1, action.getSplitBase());
        assertEquals(5, action.getSplitErt());
    }

    @Test
    void testConvertToCorporateActionList_BonusAndDividend() {
        // 1. 准备数据: 派息 + 送股
        long flags = (1L << 0) | (1L << 3); // 派息 | 送股
        QotCommon.Rehab futuRehab = QotCommon.Rehab.newBuilder()
                .setTime("2022-05-18")
                .setCompanyActFlag(flags)
                .setFwdFactorA(0.98)
                .setBwdFactorA(1.02)
                .setDividend(2.0)
                .setBonusBase(10)
                .setBonusErt(1)
                .build();

        // 2. 执行转换
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(futuRehab, STOCK_CODE);

        // 3. 断言
        assertEquals(2, result.size());

        // 验证派息
        assertTrue(result.stream().anyMatch(a ->
                a.getActionType() == CorporateActionEntity.CorporateActionType.DIVIDEND &&
                a.getDividend() == 2.0));

        // 验证送股
        assertTrue(result.stream().anyMatch(a ->
                a.getActionType() == CorporateActionEntity.CorporateActionType.BONUS &&
                a.getBonusBase() == 10 &&
                a.getBonusErt() == 1));
    }

    @Test
    void testConvertToCorporateActionList_NoAction() {
        // 1. 准备数据: 无公司行动
        QotCommon.Rehab futuRehab = QotCommon.Rehab.newBuilder()
                .setTime("2023-01-01")
                .setCompanyActFlag(0L) // 无行动
                .build();

        // 2. 执行转换
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(futuRehab, STOCK_CODE);

        // 3. 断言
        assertTrue(result.isEmpty());
    }
}
