package com.trading.infrastructure.futu;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.futu.openapi.pb.QotCommon;
import com.trading.domain.entity.CorporateActionEntity;

class FutuDataConverterTest {

    @Test
    @DisplayName("测试将Futu派息事件转换为公司行动实体")
    void testConvertToCorporateActionList_Dividend() {
        // Arrange
        double preClose = 400.0;
        double dividend = 3.4;
        QotCommon.Rehab rehab = QotCommon.Rehab.newBuilder()
                .setTime("2024-05-17")
                .setDividend(dividend)
                .build();

        // Act
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(rehab, "HK.00700",
                preClose);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);

        CorporateActionEntity entity = result.get(0);
        assertThat(entity.getActionType()).isEqualTo(CorporateActionEntity.CorporateActionType.DIVIDEND);
        assertThat(entity.getDividend()).isEqualTo(dividend);

        // 验证复权因子是否计算正确
        double expectedFactor = (preClose - dividend) / preClose; // (400 - 3.4) / 400 = 0.9915
        assertThat(entity.getBackwardAdjFactor()).isEqualTo(expectedFactor);
        assertThat(entity.getForwardAdjFactor()).isEqualTo(1 / expectedFactor);
    }

    @Test
    @DisplayName("测试将Futu拆股事件转换为公司行动实体")
    void testConvertToCorporateActionList_Split() {
        // Arrange
        double preClose = 400.0; // 拆股计算不依赖前收盘价，但方法签名需要
        QotCommon.Rehab rehab = QotCommon.Rehab.newBuilder()
                .setTime("2024-07-01")
                .setSplitBase(1)
                .setSplitErt(2) // 1股拆为2股
                .build();

        // Act
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(rehab, "HK.00700",
                preClose);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);

        CorporateActionEntity entity = result.get(0);
        assertThat(entity.getActionType()).isEqualTo(CorporateActionEntity.CorporateActionType.SPLIT);

        // 验证复权因子是否计算正确 (1股变2股，价格变为1/2)
        double expectedFactor = 1.0 / 2.0;
        assertThat(entity.getBackwardAdjFactor()).isEqualTo(expectedFactor);
        assertThat(entity.getForwardAdjFactor()).isEqualTo(1 / expectedFactor);
    }

    @Test
    @DisplayName("测试无复权事件时返回空列表")
    void testConvertToCorporateActionList_NoAction() {
        // Arrange
        double preClose = 400.0;
        QotCommon.Rehab rehab = QotCommon.Rehab.newBuilder()
                .setTime("2024-07-01")
                // No action fields set
                .build();

        // Act
        List<CorporateActionEntity> result = FutuDataConverter.convertToCorporateActionList(rehab, "HK.00700",
                preClose);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }
}
