# 港股程序化交易系统 - 整体架构设计

## 2.1 四层架构模型
```
┌─────────────────────────────────────────┐
│         CLI Layer (命令行接口层)          │
│  - CommandLineRunner                    │
│  - CommandRegistry                      │
│  - BacktestCommand                      │
│  - AbstractCommand                      │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         API Layer (接口层)               │
│  - REST Controller (预留)               │
│  - WebSocket Handler (预留)             │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      Service Layer (服务层)              │
│  - Trading Service                      │
│  - Market Service                       │
│  - Strategy Service                     │
│  - Risk Service                         │
│  - BacktestEngine                       │
│  - DataPreparationService              │
│  - BacktestReportGenerator              │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│    Infrastructure Layer (基础设施层)     │
│  - Repository                           │
│  - Market Data Adapter                  │
│  - Redis Cache                          │
│  - Disruptor/Spring Events             │
│  - HKStockCommissionCalculator          │
└─────────────────────────────────────────┘
```