package com.trading.manager;

import com.trading.common.enums.MarketType;
import com.trading.infrastructure.futu.FutuMarketDataService.KLineType;
import com.trading.infrastructure.futu.model.FutuKLine.RehabType;
import com.trading.service.HistoricalDataService;
import com.trading.service.HistoricalDataService.BatchDownloadResult;
import com.trading.service.HistoricalDataService.DownloadResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 批量下载管理器
 * 负责管理和调度历史数据的批量下载任务
 * 提供任务队列、进度跟踪、失败重试等功能
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDownloadManager {
    
    private final HistoricalDataService historicalDataService;
    
    // 下载任务管理
    private final Map<String, DownloadTask> activeTasks = new ConcurrentHashMap<>();
    private final Queue<DownloadTask> taskQueue = new LinkedList<>();
    private final Map<String, FailedTask> failedTasks = new ConcurrentHashMap<>();
    
    // 并发控制
    private final Semaphore downloadSemaphore;
    
    // 配置参数
    @Value("${historical-data.download.max-concurrent-tasks:3}")
    private int maxConcurrentTasks;
    
    @Value("${historical-data.download.auto-retry-failed:true}")
    private boolean autoRetryFailed;
    
    @Value("${historical-data.download.max-retry-attempts:3}")
    private int maxRetryAttempts;
    
    @Value("${historical-data.download.retry-delay-hours:2}")
    private int retryDelayHours;
    
    public BatchDownloadManager(HistoricalDataService historicalDataService) {
        this.historicalDataService = historicalDataService;
        this.downloadSemaphore = new Semaphore(3); // 默认值，会被配置覆盖
    }
    
    /**
     * 提交批量下载任务
     * 
     * @param symbols 股票代码列表
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param kLineType K线类型
     * @param rehabType 复权类型
     * @param priority 任务优先级
     * @return 任务ID
     */
    public String submitBatchDownloadTask(List<String> symbols,
                                         LocalDate startDate,
                                         LocalDate endDate,
                                         KLineType kLineType,
                                         RehabType rehabType,
                                         TaskPriority priority) {
        
        String taskId = generateTaskId(symbols, kLineType);
        
        DownloadTask task = DownloadTask.builder()
                .taskId(taskId)
                .symbols(new ArrayList<>(symbols))
                .startDate(startDate)
                .endDate(endDate)
                .kLineType(kLineType)
                .rehabType(rehabType)
                .priority(priority)
                .status(TaskStatus.PENDING)
                .createdTime(LocalDateTime.now())
                .retryCount(0)
                .build();
        
        // 根据优先级插入队列
        synchronized (taskQueue) {
            if (priority == TaskPriority.HIGH) {
                // 高优先级任务插入队列前部
                ((LinkedList<DownloadTask>) taskQueue).addFirst(task);
            } else {
                taskQueue.offer(task);
            }
        }
        
        log.info("批量下载任务已提交: taskId={}, symbols={}, priority={}", 
                taskId, symbols.size(), priority);
        
        // 尝试立即执行任务
        tryExecuteNextTask();
        
        return taskId;
    }
    
    /**
     * 提交单个股票下载任务
     * 
     * @param symbol 股票代码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param kLineType K线类型
     * @param rehabType 复权类型
     * @return 任务ID
     */
    public String submitSingleDownloadTask(String symbol,
                                          LocalDate startDate,
                                          LocalDate endDate,
                                          KLineType kLineType,
                                          RehabType rehabType) {
        
        return submitBatchDownloadTask(
                List.of(symbol), startDate, endDate, 
                kLineType, rehabType, TaskPriority.NORMAL);
    }
    
    /**
     * 提交市场批量下载任务
     * 为指定市场的所有股票提交下载任务
     * 
     * @param market 市场类型
     * @param symbols 该市场的股票列表
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param kLineType K线类型
     * @return 任务ID列表
     */
    public List<String> submitMarketDownloadTasks(MarketType market,
                                                 List<String> symbols,
                                                 LocalDate startDate,
                                                 LocalDate endDate,
                                                 KLineType kLineType) {
        
        log.info("提交市场批量下载任务: market={}, symbols={}, kLineType={}", 
                market, symbols.size(), kLineType);
        
        // 将股票按批次分组
        int batchSize = 20; // 每批20个股票
        List<String> taskIds = new ArrayList<>();
        
        for (int i = 0; i < symbols.size(); i += batchSize) {
            List<String> batch = symbols.subList(i, Math.min(i + batchSize, symbols.size()));
            
            String taskId = submitBatchDownloadTask(
                    batch, startDate, endDate, 
                    kLineType, RehabType.NONE, TaskPriority.NORMAL);
            
            taskIds.add(taskId);
        }
        
        return taskIds;
    }
    
    /**
     * 获取任务状态
     * 
     * @param taskId 任务ID
     * @return 任务状态信息
     */
    public TaskStatusInfo getTaskStatus(String taskId) {
        // 检查活跃任务
        DownloadTask activeTask = activeTasks.get(taskId);
        if (activeTask != null) {
            int progress = historicalDataService.getDownloadProgress(taskId);
            return TaskStatusInfo.builder()
                    .taskId(taskId)
                    .status(activeTask.getStatus())
                    .progress(progress)
                    .createdTime(activeTask.getCreatedTime())
                    .startTime(activeTask.getStartTime())
                    .symbolCount(activeTask.getSymbols().size())
                    .retryCount(activeTask.getRetryCount())
                    .message("任务执行中")
                    .build();
        }
        
        // 检查失败任务
        FailedTask failedTask = failedTasks.get(taskId);
        if (failedTask != null) {
            return TaskStatusInfo.builder()
                    .taskId(taskId)
                    .status(TaskStatus.FAILED)
                    .progress(-1)
                    .createdTime(failedTask.getOriginalTask().getCreatedTime())
                    .startTime(failedTask.getOriginalTask().getStartTime())
                    .symbolCount(failedTask.getOriginalTask().getSymbols().size())
                    .retryCount(failedTask.getOriginalTask().getRetryCount())
                    .message(failedTask.getErrorMessage())
                    .build();
        }
        
        // 检查队列中的任务
        synchronized (taskQueue) {
            for (DownloadTask task : taskQueue) {
                if (task.getTaskId().equals(taskId)) {
                    return TaskStatusInfo.builder()
                            .taskId(taskId)
                            .status(TaskStatus.PENDING)
                            .progress(0)
                            .createdTime(task.getCreatedTime())
                            .symbolCount(task.getSymbols().size())
                            .retryCount(task.getRetryCount())
                            .message("任务等待中")
                            .build();
                }
            }
        }
        
        // 任务不存在或已完成
        return null;
    }
    
    /**
     * 获取所有活跃任务状态
     * 
     * @return 活跃任务列表
     */
    public List<TaskStatusInfo> getAllActiveTasksStatus() {
        return activeTasks.keySet().stream()
                .map(this::getTaskStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 取消任务
     * 
     * @param taskId 任务ID
     * @return 是否成功取消
     */
    public boolean cancelTask(String taskId) {
        // 尝试取消活跃任务
        if (activeTasks.containsKey(taskId)) {
            boolean cancelled = historicalDataService.cancelDownloadTask(taskId);
            if (cancelled) {
                activeTasks.remove(taskId);
                log.info("任务已取消: taskId={}", taskId);
            }
            return cancelled;
        }
        
        // 从队列中移除任务
        synchronized (taskQueue) {
            boolean removed = taskQueue.removeIf(task -> task.getTaskId().equals(taskId));
            if (removed) {
                log.info("队列任务已取消: taskId={}", taskId);
            }
            return removed;
        }
    }
    
    /**
     * 重试失败任务
     * 
     * @param taskId 任务ID
     * @return 是否成功提交重试
     */
    public boolean retryFailedTask(String taskId) {
        FailedTask failedTask = failedTasks.get(taskId);
        if (failedTask == null) {
            log.warn("未找到失败任务: taskId={}", taskId);
            return false;
        }
        
        DownloadTask originalTask = failedTask.getOriginalTask();
        
        // 检查重试次数限制
        if (originalTask.getRetryCount() >= maxRetryAttempts) {
            log.warn("任务重试次数已达上限: taskId={}, retryCount={}", 
                    taskId, originalTask.getRetryCount());
            return false;
        }
        
        // 增加重试次数并重新提交
        originalTask.setRetryCount(originalTask.getRetryCount() + 1);
        originalTask.setStatus(TaskStatus.PENDING);
        originalTask.setCreatedTime(LocalDateTime.now());
        
        synchronized (taskQueue) {
            // 重试任务具有高优先级
            ((LinkedList<DownloadTask>) taskQueue).addFirst(originalTask);
        }
        
        failedTasks.remove(taskId);
        
        log.info("失败任务已重新提交: taskId={}, retryCount={}", 
                taskId, originalTask.getRetryCount());
        
        tryExecuteNextTask();
        return true;
    }
    
    /**
     * 清理已完成和失败的任务
     * 
     * @param olderThanHours 清理多少小时前的任务
     */
    public void cleanupOldTasks(int olderThanHours) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(olderThanHours);
        
        // 清理失败任务
        failedTasks.entrySet().removeIf(entry -> {
            boolean shouldRemove = entry.getValue().getFailedTime().isBefore(cutoffTime);
            if (shouldRemove) {
                log.debug("清理过期失败任务: taskId={}", entry.getKey());
            }
            return shouldRemove;
        });
        
        log.info("任务清理完成，清理时间早于: {}", cutoffTime);
    }
    
    /**
     * 获取下载统计信息
     * 
     * @return 统计信息
     */
    public DownloadStatistics getDownloadStatistics() {
        int activeCount = activeTasks.size();
        int pendingCount;
        
        synchronized (taskQueue) {
            pendingCount = taskQueue.size();
        }
        
        int failedCount = failedTasks.size();
        
        // 按优先级统计待处理任务
        Map<TaskPriority, Integer> pendingByPriority = new HashMap<>();
        synchronized (taskQueue) {
            for (DownloadTask task : taskQueue) {
                pendingByPriority.merge(task.getPriority(), 1, Integer::sum);
            }
        }
        
        // 按K线类型统计活跃任务
        Map<KLineType, Integer> activeByKLineType = activeTasks.values().stream()
                .collect(Collectors.groupingBy(
                        DownloadTask::getKLineType,
                        Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
                ));
        
        return DownloadStatistics.builder()
                .activeTaskCount(activeCount)
                .pendingTaskCount(pendingCount)
                .failedTaskCount(failedCount)
                .maxConcurrentTasks(maxConcurrentTasks)
                .pendingByPriority(pendingByPriority)
                .activeByKLineType(activeByKLineType)
                .lastUpdateTime(LocalDateTime.now())
                .build();
    }
    
    /**
     * 定时任务：处理下载队列
     */
    @Scheduled(fixedDelay = 5000) // 每5秒执行一次
    public void processDownloadQueue() {
        tryExecuteNextTask();
    }
    
    /**
     * 定时任务：重试失败任务
     */
    @Scheduled(fixedDelay = 3600000) // 每小时执行一次
    public void retryFailedTasks() {
        if (!autoRetryFailed) {
            return;
        }
        
        LocalDateTime retryTime = LocalDateTime.now().minusHours(retryDelayHours);
        
        List<String> tasksToRetry = failedTasks.entrySet().stream()
                .filter(entry -> entry.getValue().getFailedTime().isBefore(retryTime))
                .filter(entry -> entry.getValue().getOriginalTask().getRetryCount() < maxRetryAttempts)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        for (String taskId : tasksToRetry) {
            if (retryFailedTask(taskId)) {
                log.info("自动重试失败任务: taskId={}", taskId);
            }
        }
    }
    
    /**
     * 定时任务：清理过期任务
     */
    @Scheduled(fixedDelay = 86400000) // 每24小时执行一次
    public void cleanupExpiredTasks() {
        cleanupOldTasks(72); // 清理72小时前的任务
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 尝试执行下一个任务
     */
    @Async
    public void tryExecuteNextTask() {
        if (!downloadSemaphore.tryAcquire()) {
            // 无可用并发槽位
            return;
        }
        
        DownloadTask task;
        synchronized (taskQueue) {
            task = taskQueue.poll();
        }
        
        if (task == null) {
            // 队列为空，释放信号量
            downloadSemaphore.release();
            return;
        }
        
        executeTask(task);
    }
    
    /**
     * 执行下载任务
     * 
     * @param task 下载任务
     */
    private void executeTask(DownloadTask task) {
        String taskId = task.getTaskId();
        
        try {
            log.info("开始执行下载任务: taskId={}, symbols={}, kLineType={}", 
                    taskId, task.getSymbols().size(), task.getKLineType());
            
            // 更新任务状态
            task.setStatus(TaskStatus.RUNNING);
            task.setStartTime(LocalDateTime.now());
            activeTasks.put(taskId, task);
            
            // 执行批量下载
            CompletableFuture<BatchDownloadResult> future = historicalDataService
                    .downloadBatchHistoricalData(
                            task.getSymbols(),
                            task.getStartDate(),
                            task.getEndDate(),
                            task.getKLineType(),
                            task.getRehabType()
                    );
            
            // 处理下载结果
            future.whenComplete((result, throwable) -> {
                try {
                    if (throwable != null) {
                        handleTaskFailure(task, throwable.getMessage());
                    } else {
                        handleTaskSuccess(task, result);
                    }
                } finally {
                    // 移除活跃任务并释放信号量
                    activeTasks.remove(taskId);
                    downloadSemaphore.release();
                    
                    // 尝试执行下一个任务
                    tryExecuteNextTask();
                }
            });
            
        } catch (Exception e) {
            log.error("执行下载任务异常: taskId={}", taskId, e);
            handleTaskFailure(task, e.getMessage());
            activeTasks.remove(taskId);
            downloadSemaphore.release();
            tryExecuteNextTask();
        }
    }
    
    private void handleTaskSuccess(DownloadTask task, BatchDownloadResult result) {
        log.info("下载任务完成: taskId={}, 成功={}, 失败={}, 耗时={}ms", 
                task.getTaskId(), result.successCount(), result.failedCount(), result.totalTimeMs());
        
        task.setStatus(TaskStatus.COMPLETED);
        task.setEndTime(LocalDateTime.now());
        
        // 记录结果摘要
        log.info("任务结果摘要: {}", result.summary());
    }
    
    private void handleTaskFailure(DownloadTask task, String errorMessage) {
        log.error("下载任务失败: taskId={}, error={}", task.getTaskId(), errorMessage);
        
        task.setStatus(TaskStatus.FAILED);
        task.setEndTime(LocalDateTime.now());
        
        // 添加到失败任务列表
        FailedTask failedTask = FailedTask.builder()
                .taskId(task.getTaskId())
                .originalTask(task)
                .errorMessage(errorMessage)
                .failedTime(LocalDateTime.now())
                .build();
        
        failedTasks.put(task.getTaskId(), failedTask);
    }
    
    private String generateTaskId(List<String> symbols, KLineType kLineType) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        int symbolsHash = symbols.hashCode();
        return String.format("batch_%s_%s_%d", kLineType.name(), timestamp, Math.abs(symbolsHash));
    }
    
    // ==================== 数据结构定义 ====================
    
    /**
     * 下载任务
     */
    @lombok.Data
    @lombok.Builder
    public static class DownloadTask {
        private String taskId;
        private List<String> symbols;
        private LocalDate startDate;
        private LocalDate endDate;
        private KLineType kLineType;
        private RehabType rehabType;
        private TaskPriority priority;
        private TaskStatus status;
        private LocalDateTime createdTime;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private int retryCount;
    }
    
    /**
     * 失败任务
     */
    @lombok.Data
    @lombok.Builder
    public static class FailedTask {
        private String taskId;
        private DownloadTask originalTask;
        private String errorMessage;
        private LocalDateTime failedTime;
    }
    
    /**
     * 任务状态信息
     */
    @lombok.Data
    @lombok.Builder
    public static class TaskStatusInfo {
        private String taskId;
        private TaskStatus status;
        private int progress;
        private LocalDateTime createdTime;
        private LocalDateTime startTime;
        private int symbolCount;
        private int retryCount;
        private String message;
    }
    
    /**
     * 下载统计信息
     */
    @lombok.Data
    @lombok.Builder
    public static class DownloadStatistics {
        private int activeTaskCount;
        private int pendingTaskCount;
        private int failedTaskCount;
        private int maxConcurrentTasks;
        private Map<TaskPriority, Integer> pendingByPriority;
        private Map<KLineType, Integer> activeByKLineType;
        private LocalDateTime lastUpdateTime;
    }
    
    /**
     * 任务优先级
     */
    public enum TaskPriority {
        LOW("低"),
        NORMAL("普通"),
        HIGH("高"),
        URGENT("紧急");
        
        private final String description;
        
        TaskPriority(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 任务状态
     */
    public enum TaskStatus {
        PENDING("待处理"),
        RUNNING("运行中"),
        COMPLETED("已完成"),
        FAILED("失败"),
        CANCELLED("已取消");
        
        private final String description;
        
        TaskStatus(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
}