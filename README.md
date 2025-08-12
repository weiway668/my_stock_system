# Hong Kong Stock Trading System

## 项目简介

港股程序化交易系统 - 基于Spring Boot 3.2的高性能算法交易平台，专门针对港股ETF和大盘股设计。系统采用MACD、BOLL、成交量三重技术指标组合策略，目标实现15-20%的年化收益率。

## 技术特性

- **高性能架构**: 使用Disruptor内存队列，单线程吞吐量>1000万/秒
- **多策略支持**: MACD趋势跟踪、BOLL均值回归、量价突破策略
- **四层过滤系统**: 波动率、成交量、趋势确认、风险评估
- **动态风控**: Kelly公式仓位管理，ATR止损，最大回撤控制
- **CLI交互**: JLine3命令行界面，支持回测和实盘交易
- **多环境配置**: 开发(H2)、生产(MySQL)环境分离

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+
- MySQL 8.0 (生产环境)
- Redis 7.2 (可选)

### 构建项目

```bash
# 克隆项目
git clone <repository-url>
cd my_stock_system

# 安装依赖并构建
mvn clean install

# 运行测试
mvn test
```

### 运行应用

```bash
# 开发环境（使用H2内存数据库）
mvn spring-boot:run -Dspring.profiles.active=dev

# 生产环境（使用MySQL数据库）
mvn spring-boot:run -Dspring.profiles.active=prod

# CLI模式
java -jar target/hk-stock-trading-system.jar --cli
```

### Docker部署

```bash
# 构建Docker镜像
docker build -t hk-trading-system .

# 使用docker-compose启动
docker-compose up -d
```

## 项目结构

```
my_stock_system/
├── src/
│   ├── main/
│   │   ├── java/com/trading/
│   │   │   ├── domain/          # 领域模型
│   │   │   │   ├── entity/      # 实体类
│   │   │   │   ├── enums/       # 枚举类型
│   │   │   │   └── vo/          # 值对象
│   │   │   ├── infrastructure/  # 基础设施
│   │   │   │   ├── config/      # 配置类
│   │   │   │   ├── event/       # 事件处理
│   │   │   │   └── repository/  # 数据访问
│   │   │   ├── service/         # 业务服务
│   │   │   ├── api/             # REST API
│   │   │   └── common/          # 公共工具
│   │   └── resources/
│   │       ├── application.yml  # 主配置
│   │       ├── application-dev.yml  # 开发配置
│   │       ├── application-prod.yml # 生产配置
│   │       └── logback-spring.xml   # 日志配置
│   └── test/                    # 测试代码
├── doc/                         # 项目文档
├── pom.xml                      # Maven配置
└── README.md
```

## 核心功能

### 1. 实时行情处理
- 富途OpenAPI接入
- 30分钟/120分钟K线数据
- 技术指标实时计算

### 2. 策略引擎
- MACD趋势策略 (权重35%)
- BOLL回归策略 (权重35%)
- 量价突破策略 (权重30%)
- 自适应市场状态切换

### 3. 风险管理
- 最大回撤控制 (<15%)
- 动态仓位管理
- ATR止损系统
- 资金管理 (单笔最大5万港币)

### 4. CLI命令
```bash
# 回测命令
backtest --strategy MACD --symbol 2800.HK --start 2024-01-01 --end 2024-12-31

# 实盘交易
trade --mode PAPER --capital 100000

# 数据下载
data download --symbol 00700.HK --period 30m --days 90

# 策略优化
optimize --strategy ALL --method GRID
```

## 配置说明

### 交易配置
```yaml
trading:
  capital:
    total: 100000        # 总资金
    max-position: 50000  # 单笔最大
  risk:
    max-daily-loss: 0.05 # 日亏损5%
    max-drawdown: 0.15   # 最大回撤15%
```

### 策略参数
```yaml
strategies:
  macd:
    fast-period: 12
    slow-period: 26
    signal-period: 9
```

## API文档

启动应用后访问:
- Swagger UI: http://localhost:8080/swagger-ui.html
- API Docs: http://localhost:8080/api-docs

## 监控指标

- Prometheus Metrics: http://localhost:8080/actuator/prometheus
- Health Check: http://localhost:8080/actuator/health

## 开发指南

### 代码规范
- 使用Lombok减少样板代码
- 遵循Spring Boot最佳实践
- 单元测试覆盖率>90%

### 提交规范
```bash
git commit -m "feat: 添加新功能"
git commit -m "fix: 修复问题"
git commit -m "docs: 更新文档"
```

## 性能指标

- Disruptor队列延迟: <1微秒
- 策略计算延迟: <50毫秒
- 订单执行延迟: <100毫秒
- 系统吞吐量: >1000 TPS

## 许可证

私有项目，保留所有权利。

## 联系方式

如有问题，请查阅项目文档或提交Issue。

---
*构建时间: 2024*