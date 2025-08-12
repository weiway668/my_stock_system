# 港股交易系统 - Claude 项目上下文

## 重要提示：请始终使用中文回复
**所有与此项目相关的回复、代码注释、文档说明都必须使用中文。这是项目的强制性要求。**

## 项目概述
这是一个基于 Spring Boot 3.2.0 构建的香港股票算法交易系统，专为高性能交易设计，具备实时市场数据处理、技术分析和自动化交易功能。

## 技术栈
- **Java版本**: 17 (Amazon Corretto)
- **框架**: Spring Boot 3.2.0
- **数据库**: MySQL 8.2.0, H2 (测试用), Redis (缓存)
- **高性能组件**: Disruptor 3.4.4 (高性能队列)
- **实时通信**: WebSocket 实时市场数据流
- **命令行界面**: Spring Shell 3.1.5, JLine 3.23.0
- **技术分析**: TA4J 0.15
- **测试框架**: JUnit 5, Mockito 5, Testcontainers
- **监控**: Micrometer, Prometheus
- **构建工具**: Maven 3.8.0+

## 核心功能
- 基于 WebSocket 的实时市场数据处理
- 使用 Disruptor 模式的高性能订单处理
- 技术分析指标和交易策略
- 交易操作的命令行界面
- 风险管理和持仓跟踪
- Prometheus 指标监控
- Testcontainers 集成测试

## 开发环境配置

### Maven 配置
- **本地仓库路径**: `/Users/weiway/mvn_repository/repository`
- **国内镜像源**: 阿里云(主要), 腾讯云, 华为云
- **配置文件**: `settings.xml` (需复制到 `~/.m2/settings.xml`)

### JDK 配置
- **必需版本**: JDK 17
- **当前问题**: 系统默认是 JDK 8, 需要切换到 JDK 17
- **可用的 JDK 17**: `/Users/weiway/Library/Java/JavaVirtualMachines/corretto-17.0.12/Contents/Home`
- **切换方法**: 需要设置 JAVA_HOME 环境变量

### 构建命令
```bash
# 使用项目的 settings.xml
mvn -s settings.xml clean install

# 标准 Maven 命令
mvn clean compile
mvn test
mvn spring-boot:run
```

### 测试命令
```bash
# 运行单元测试
mvn test

# 生成覆盖率报告
mvn jacoco:report

# 集成测试(包含 Testcontainers)
mvn verify
```

## 项目结构
- **核心交易引擎**: 高性能订单处理
- **市场数据模块**: 实时 WebSocket 连接
- **技术分析模块**: TA4J 集成的技术指标
- **命令行界面**: Spring Shell 交易操作命令
- **配置管理**: Spring Boot 自动配置
- **监控模块**: Actuator 端点, Prometheus 指标

## 开发规范
1. **Java 17 特性**: 使用现代 Java 语法和 API
2. **Spring Boot 最佳实践**: 遵循 Spring 约定
3. **性能优化**: 在关键路径使用 Disruptor
4. **测试覆盖**: 编写全面的单元测试和集成测试
5. **代码风格**: 遵循 Java 命名规范, 使用 Lombok
6. **文档维护**: 更新 OpenAPI/Swagger 文档
7. **中文注释**: 所有注释和文档必须使用中文

## 常见问题与解决方案
1. **JDK 版本问题**: 确保使用 JDK 17 (不是 JDK 8)
2. **Maven 下载慢**: 使用国内镜像源加速依赖解析
3. **WebSocket 连接**: 优雅处理连接失败
4. **数据库配置**: 测试用 H2, 生产环境用 MySQL

## 依赖管理
- 父级 POM: Spring Boot Starter Parent 3.2.0
- 核心依赖: Spring Web, JPA, Redis, WebSocket, Actuator
- 测试依赖: JUnit 5, Mockito, AssertJ, Testcontainers
- 工具库: Guava, Commons Lang3, Commons Math3

## 监控与可观测性
- **Actuator 端点**: 健康检查, 指标, 信息
- **Prometheus**: 交易操作自定义指标
- **日志记录**: 结构化日志和适当的日志级别
- **重试机制**: Spring Retry 提供弹性操作

## 安全考虑
- **敏感数据**: 绝不记录 API 密钥、密码或交易凭证
- **输入验证**: 验证所有交易参数
- **限流**: 为 API 调用实施适当的限流
- **审计跟踪**: 记录所有交易操作以满足合规要求

## 可用的命令行工具
系统包含 Spring Shell CLI 界面，用于:
- 市场数据查询
- 订单管理
- 投资组合分析
- 系统监控
- 配置管理

## Claude 开发注意事项
- **语言要求**: 所有回复必须使用中文
- **JDK 兼容性**: 确保所有代码更改兼容 JDK 17
- **Maven 配置**: 使用项目的 settings.xml 进行依赖解析
- **代码规范**: 遵循现有的代码模式和 Spring Boot 约定
- **测试执行**: 重大更改后运行测试
- **性能考虑**: 考虑交易关键代码路径的性能影响
- **日志记录**: 维护全面的中文日志用于问题排查
- **中文注释**: 所有新增代码必须包含中文注释
每完成一个Phase进行Git提交
- 优先实现高优先级功能
- 确保每个功能都有对应的单元测试
- 所有代码和注释使用中文
- 定期运行性能测试确保系统效率