package com.pdfutil.pdf.config;

import com.pdfutil.common.core.utils.DualLayerPdfConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * PDF转换线程池配置
 *
 * 使用线程池管理转换任务，避免无限制创建线程导致OOM
 *
 * @author Alika
 * @date 2025-02-06
 */
@Configuration
public class PdfConvertThreadPool {

    private static final Logger log = LoggerFactory.getLogger(PdfConvertThreadPool.class);

    // OCR引擎配置（从application.yml读取）
    // 可选值: paddle (PaddleOCR), rapid (RapidOCR推荐)
    @Value("${pdfutil.pdf.ocrType:rapid}")
    private String ocrType;

    // OCR引擎版本: v4 或 v5
    @Value("${pdfutil.pdf.ocrVersion:}")
    private String ocrVersion;

    // RapidOCR模型目录
    @Value("${pdfutil.pdf.rapidOcrModelDir:}")
    private String rapidOcrModelDir;

    // PaddleOCR脚本路径（兼容配置）
    @Value("${pdfutil.pdf.paddleOcrScriptPath:./scripts/paddleocr_wrapper.py}")
    private String paddleOcrScriptPath;

    // RapidOCR脚本路径
    @Value("${pdfutil.pdf.rapidOcrScriptPath:./scripts/rapidocr_wrapper.py}")
    private String rapidOcrScriptPath;

    /**
     * 初始化OCR配置
     * 在应用启动时自动调用
     *
     * 注意：OCR系统属性已由 PdfUtilApplication 统一设置，此处仅记录日志
     */
    @PostConstruct
    public void initOcrConfig() {
        try {
            log.info("开始初始化OCR配置...");
            log.info("当前OCR引擎: {}", ocrType);

            // 验证系统属性是否已设置
            String ocrEngine = System.getProperty("ocr.engine");
            String ocrType = System.getProperty("ocr.type");
            log.info("系统属性 ocr.engine={}, ocr.type={}", ocrEngine, ocrType);

            // 记录当前OCR配置状态（不再重复设置系统属性）
            if ("rapid".equalsIgnoreCase(this.ocrType)) {
                log.info("使用 RapidOCR 引擎（高性能，完全离线）");
            } else if ("paddle".equalsIgnoreCase(this.ocrType)) {
                log.info("使用 PaddleOCR 引擎");
            } else {
                log.warn("不支持的OCR引擎类型: {}, 使用默认的RapidOCR", this.ocrType);
            }

            log.info("OCR配置初始化成功");

        } catch (Exception e) {
            log.error("OCR配置初始化失败", e);
            // 不抛出异常，允许应用继续启动
        }
    }

    /**
     * PDF转换专用线程池
     *
     * 【优化说明】针对8GB可用内存的并发优化（实际应用场景）：
     * - 系统总内存16GB，但需运行其他软件
     * - 实际可用给PDF转换的内存：8GB
     * - OCR任务内存占用：每个任务约 400MB-600MB
     * - 8GB可用内存建议并发：3-5个（保守配置，保证系统流畅）
     * - 使用动态计算：根据可用内存自动调整
     *
     * 核心配置：
     * - 核心线程数：基于内存计算的安全并发数
     * - 最大线程数：核心数 * 1.2（避免过度并发导致OOM）
     * - 队列容量：200（增加缓冲，避免任务丢失）
     * - 拒绝策略：调用者运行（保证任务不丢失）
     *
     * @return 线程池执行器
     */
    @Bean(name = "pdfConvertExecutor")
    public Executor pdfConvertExecutor() {
        log.info("初始化PDF转换线程池...");

        int cpuCore = Runtime.getRuntime().availableProcessors();
        long totalMemoryMB = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long systemMemoryMB = getSystemTotalMemoryMB();
        
        log.info("系统信息 - CPU核心数: {}, JVM最大内存: {}MB, 系统总内存: {}MB", 
            cpuCore, totalMemoryMB, systemMemoryMB);

        // 【优化】基于8GB可用内存计算安全的并发数
        // PaddleOCR: 每个任务约 600MB 内存
        // RapidOCR: 每个任务约 400MB 内存（更节省内存）
        boolean isRapidOcr = "rapid".equalsIgnoreCase(ocrType);
        int memoryPerTask = isRapidOcr ? 400 : 600;

        log.info("OCR引擎: {}, 每任务内存: {}MB", ocrType, memoryPerTask);

        // 【修改】使用固定的8GB可用内存（实际应用场景）
        // 系统总内存16GB，但需要运行其他软件，实际可用约8GB
        long availableMemoryMB = 8192; // 固定使用8GB可用内存
        long reservedMemoryMB = 2048;  // 保留2GB给系统和其他进程
        long usableMemoryMB = Math.max(availableMemoryMB - reservedMemoryMB, 2048); // 至少保留2GB可用
        
        // 计算基于内存的并发数
        int memoryBasedConcurrency = (int) (usableMemoryMB / memoryPerTask);
        
        // 计算基于CPU的并发数（OCR是CPU密集型）
        // 在8GB内存限制下，保守配置，避免过度并发
        int cpuMultiplier = isRapidOcr ? 2 : 2;
        int cpuBasedConcurrency = cpuCore * cpuMultiplier;

        // 【用户配置】针对8GB可用内存的保守并发配置
        // PaddleOCR: 3个 (每任务600MB，约1.8GB)
        // RapidOCR: 4个 (每任务400MB，约1.6GB)
        int userPreferredConcurrency = isRapidOcr ? 4 : 3;

        // 取用户偏好值、内存计算值、CPU计算值的最小值，但设置安全上下限
        // 最低2个并发（保证基本效率，避免系统过载）
        // PaddleOCR最高4个，RapidOCR最高5个（8GB内存限制）
        int maxConcurrency = isRapidOcr ? 5 : 4;
        int optimalConcurrency = Math.max(2, Math.min(maxConcurrency,
            Math.min(userPreferredConcurrency, Math.min(memoryBasedConcurrency, cpuBasedConcurrency))));

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：根据内存和CPU动态计算的最优并发数
        executor.setCorePoolSize(optimalConcurrency);

        // 最大线程数：根据OCR引擎动态调整（8GB内存下的保守配置）
        int maxPoolSizeLimit = isRapidOcr ? 6 : 5;
        int maxPoolSize = Math.min(maxPoolSizeLimit, (int) (optimalConcurrency * 1.2));
        executor.setMaxPoolSize(maxPoolSize);

        // 队列容量：增加到200，更好的缓冲能力
        executor.setQueueCapacity(200);

        // 线程空闲时间（秒）：缩短到30秒，更快释放资源
        executor.setKeepAliveSeconds(30);

        // 线程名称前缀
        executor.setThreadNamePrefix("pdf-convert-");

        // 拒绝策略：由调用线程处理（保证任务不丢失）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(300);

        executor.initialize();

        log.info("PDF转换线程池初始化完成 - CPU核心:{}, 可用内存:8GB(固定), OCR引擎:{}, 计算并发数:{}"
                + " - 核心线程:{}, 最大线程:{}, 队列容量:{}",
            cpuCore, ocrType, optimalConcurrency,
            optimalConcurrency, maxPoolSize, 200);

        // 内存使用提示
        log.info("【配置说明】实际可用内存: 8GB (保守配置，保证系统流畅)");
        log.info("【性能提示】如需更高并发，请确保系统可用内存充足或关闭其他应用程序");

        return executor;
    }
    
    /**
     * 获取系统总内存（MB）
     * 用于更准确地计算并发数
     */
    private long getSystemTotalMemoryMB() {
        try {
            com.sun.management.OperatingSystemMXBean osBean = 
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return osBean.getTotalPhysicalMemorySize() / 1024 / 1024;
        } catch (Exception e) {
            log.debug("无法获取系统总内存: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 进度更新线程池
     *
     * 用于执行进度更新等轻量级任务
     *
     * @return 线程池执行器
     */
    @Bean(name = "progressUpdateExecutor")
    public Executor progressUpdateExecutor() {
        log.info("初始化进度更新线程池...");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：较小，因为进度更新是轻量级任务
        executor.setCorePoolSize(5);

        // 最大线程数
        executor.setMaxPoolSize(10);

        // 队列容量
        executor.setQueueCapacity(200);

        // 线程空闲时间（秒）
        executor.setKeepAliveSeconds(30);

        // 线程名称前缀
        executor.setThreadNamePrefix("progress-update-");

        // 拒绝策略：记录日志并丢弃
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("进度更新任务被拒绝，队列已满 - Active: {}, Completed: {}, Task: {}",
                e.getActiveCount(), e.getCompletedTaskCount(), r.toString());
        });

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("进度更新线程池初始化完成 - 核心:5, 最大:10, 队列:200");

        return executor;
    }
}
