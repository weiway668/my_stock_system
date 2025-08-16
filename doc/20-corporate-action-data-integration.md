# 20 - 除权除息数据集成文档

## 1. 概述

本文档旨在说明本交易系统如何从外部数据源获取、处理并存储股票的历史除权除息（公司行动）数据。这些数据对于计算真实的复权股价和进行精确的回测至关重要。

## 2. 数据源

- **服务提供商**: 富途 (Futu Open API)
- **API接口**: `Qot_RequestRehab`
- **功能**: 获取指定股票的历史复权因子，其中包含了详细的公司行动信息，如派息、送股、配股、拆合股等。
- **官方文档**: [Futu API - 获取复权因子](https://openapi.futunn.com/futu-api-doc/quote/get-rehab.html)

## 3. 实现流程

获取除权除息数据的核心流程如下：

1.  **服务调用**: 上层服务（如 `HistoricalDataService`）通过注入的 `FutuMarketDataService` 接口发起调用。

2.  **接口请求**: 调用 `FutuMarketDataService.getRehab(stockCode)` 方法。

3.  **Futu API通信**: `FutuMarketDataServiceImpl` 作为实现类，调用 `FutuWebSocketClient.getRehabSync()` 方法，后者负责构造请求并与 Futu API 服务器进行同步通信，获取原始的复权数据。

4.  **数据转换**: `FutuDataConverter.convertToCorporateActionList()` 静态方法被调用，负责将 Futu API 返回的 Protobuf 格式的 `Rehab` 对象进行解析和转换。
    - **关键逻辑**: 此转换方法会解码 `Rehab` 对象中的 `companyActFlag` 位掩码，以识别出当天发生的一种或多种公司行动（例如，可能同时发生派息和送股）。
    - **产出**: 为每项识别出的公司行动创建一个 `CorporateActionEntity` 实例，并填充所有详细信息（如派息金额、配股比例、配股价等）。

5.  **返回实体**: 方法最终返回一个 `List<CorporateActionEntity>`，其中包含了该股票所有历史公司行动的详细记录。

## 4. 核心数据模型

所有公司行动数据都被标准化为 `CorporateActionEntity` 实体，其核心字段如下：

- `stockCode`: 股票代码
- `exDividendDate`: 除权除息日
- `actionType`: 公司行动类型 (枚举: `DIVIDEND`, `SPLIT`, `MERGE`, `BONUS`, `RIGHTS_ISSUE`)
- `forwardAdjFactor`: 前复权因子
- `backwardAdjFactor`: 后复权因子
- **详细信息字段**: 根据 `actionType` 的不同，会填充以下特定字段：
  - `dividend`, `spDividend`: 派息金额
  - `splitBase`, `splitErt`: 拆股比例
  - `joinBase`, `joinErt`: 合股比例
  - `bonusBase`, `bonusErt`: 送股比例
  - `allotBase`, `allotErt`, `allotPrice`: 配股比例和价格

## 5. 调用方法示例

在系统其他服务中，可以通过依赖注入 `FutuMarketDataService` 来方便地获取指定股票的完整公司行动历史。

```java
@Service
public class MyDataService {

    @Autowired
    private FutuMarketDataService futuMarketDataService;

    @Autowired
    private CorporateActionRepository corporateActionRepository;

    /**
     * 获取并持久化指定股票的公司行动数据
     */
    @Transactional
    public void fetchAndStoreCorporateActions(String stockCode) {
        // 1. 从Futu API获取数据
        List<CorporateActionEntity> actions = futuMarketDataService.getRehab(stockCode);

        if (actions != null && !actions.isEmpty()) {
            // 2. 打印日志或进行其他处理
            actions.forEach(action -> {
                log.info("获取到公司行动: {}, 日期: {}, 类型: {}", 
                    action.getStockCode(), 
                    action.getExDividendDate(), 
                    action.getActionType());
            });

            // 3. 存入数据库
            corporateActionRepository.saveAll(actions);
        } else {
            log.info("股票 {} 没有公司行动数据。", stockCode);
        }
    }
}
```
