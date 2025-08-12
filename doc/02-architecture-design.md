# 港股程序化交易系统 - 整体架构设计

## 2.1 三层架构模型
```
┌─────────────────────────────────────────┐
│         API Layer (接口层)               │
│  - REST Controller                      │
│  - WebSocket Handler                    │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      Service Layer (服务层)              │
│  - Trading Service                      │
│  - Market Service                       │
│  - Strategy Service                     │
│  - Risk Service                         │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│    Infrastructure Layer (基础设施层)     │
│  - Repository                           │
│  - Market Data Adapter                  │
│  - Redis Cache                          │
│  - Disruptor/Spring Events             │
└─────────────────────────────────────────┘
```