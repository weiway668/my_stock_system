# 港股程序化交易系统 - 架构文档

## 项目简介

本系统是专门针对港股ETF和大盘股的程序化交易系统，采用MACD、BOLL、成交量三重技术指标组合策略，目标实现15-20%的年化收益率。系统使用Java SpringBoot框架，支持T+0交易，采用30分钟主周期配合120分钟确认周期的多时间框架策略。

## 核心特性

- **目标收益**: 年化15-20%
- **最大回撤**: <15%
- **交易模式**: T+0日内及波段结合
- **技术架构**: Spring Boot 3.2 + 内存队列（Disruptor）
- **策略体系**: MACD趋势跟踪、BOLL均值回归、量价突破策略

## 文档目录

### 基础架构
- [01-系统概述](01-system-overview.md) - 项目背景、核心指标、技术栈、策略体系
- [02-架构设计](02-architecture-design.md) - 三层架构模型设计
- [03-领域模型](03-domain-models.md) - 市场数据、订单、持仓等核心实体

### 业务服务
- [04-服务层设计](04-service-layer.md) - 市场数据、策略、交易、风险管理服务
- [05-策略配置管理](05-strategy-config.md) - 策略配置实体与管理服务
- [11-自适应策略系统](11-adaptive-strategy.md) - 市场状态识别与策略切换

### 基础设施
- [06-日志管理系统](06-logging-system.md) - 结构化日志与AOP切面
- [07-基础设施层](07-infrastructure.md) - 数据访问、内存队列、Redis缓存
- [08-SpringBoot配置](08-springboot-config.md) - 应用主类与配置文件

### 运维监控
- [09-监控系统](09-monitoring-system.md) - 自定义监控服务与API
- [10-质量保证](10-quality-assurance.md) - 架构约束、设计模式、插件化架构

### 分析与测试
- [12-回测框架](12-backtest-framework.md) - 预期收益模型与回测实现
- [13-阈值配置管理](13-threshold-config.md) - 配置分层、阈值分析、A/B测试

### 交互与优化
- [14-CLI命令行系统](14-cli-system.md) - 命令行交易、回测、数据管理工具
- [15-内存队列优化](15-memory-queue-optimization.md) - Disruptor性能优化配置
- [16-开发工作流](16-development-workflow.md) - 核心组件开发流程指南

## 快速开始

1. **环境准备**
   ```bash
   # Java 17+
   java -version
   
   # Maven 3.8+
   mvn -version
   ```

2. **项目构建**
   ```bash
   mvn clean install
   ```

3. **运行系统**
   ```bash
   # 开发环境（H2内存数据库）
   mvn spring-boot:run -Dspring.profiles.active=dev
   
   # 生产环境（MySQL数据库）
   mvn spring-boot:run -Dspring.profiles.active=prod
   ```

4. **CLI交互**
   ```bash
   # 进入交互式Shell
   java -jar target/trading-system.jar
   
   # 执行单个命令
   java -jar target/trading-system.jar backtest --symbol 2800.HK --start 2024-01-01
   ```

## 技术栈

- **核心框架**: Spring Boot 3.2
- **消息队列**: Disruptor + Spring Events
- **缓存**: Redis 7.2
- **数据库**: H2（开发）/ MySQL 8.0（生产）
- **券商接口**: 富途OpenAPI
- **CLI框架**: JLine3 + Spring Shell

## 联系方式

如有问题或建议，请通过Issue反馈。