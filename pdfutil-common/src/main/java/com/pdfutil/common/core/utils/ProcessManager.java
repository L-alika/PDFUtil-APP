package com.pdfutil.common.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程资源管理工具类
 *
 * 功能特性：
 * - 统一的进程创建和管理
 * - 超时自动终止（避免僵尸进程）
 * - 异步读取输出流（避免阻塞）
 * - 进程资源追踪和监控
 * - 优雅的资源释放
 *
 * @author Alika
 * @date 2025-03-20
 */
public class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    /** 活跃进程追踪 */
    private static final Map<Long, ProcessInfo> activeProcesses = new ConcurrentHashMap<>();

    /** 进程ID生成器 */
    private static final AtomicLong processIdGenerator = new AtomicLong(0);

    /** 清理线程 */
    private static volatile Thread cleanupThread = null;

    static {
        // 启动清理线程，定期检查僵尸进程
        startCleanupThread();
    }

    /**
     * 进程信息
     */
    public static class ProcessInfo {
        final long id;
        final Process process;
        final String command;
        final long startTime;
        final String threadName;
        volatile boolean finished = false;

        ProcessInfo(long id, Process process, String command) {
            this.id = id;
            this.process = process;
            this.command = command;
            this.startTime = System.currentTimeMillis();
            this.threadName = Thread.currentThread().getName();
        }

        public long getRunningTime() {
            return System.currentTimeMillis() - startTime;
        }

        public boolean isAlive() {
            return process.isAlive();
        }
    }

    /**
     * 执行结果
     */
    public static class ProcessResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final long executionTime;

        public ProcessResult(int exitCode, String stdout, String stderr, long executionTime) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.executionTime = executionTime;
        }

        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public long getExecutionTime() { return executionTime; }
        public boolean isSuccess() { return exitCode == 0; }
    }

    /**
     * 启动清理线程
     */
    private static synchronized void startCleanupThread() {
        if (cleanupThread != null && cleanupThread.isAlive()) {
            return;
        }

        cleanupThread = new Thread(() -> {
            log.info("进程清理线程启动");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 每30秒检查一次
                    Thread.sleep(30000);
                    cleanupZombieProcesses();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("清理线程异常: {}", e.getMessage());
                }
            }
            log.info("进程清理线程退出");
        }, "ProcessCleanupThread");

        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * 清理僵尸进程
     */
    private static void cleanupZombieProcesses() {
        int cleanedCount = 0;
        long maxRunTime = 10 * 60 * 1000; // 10分钟超时

        // 使用迭代器安全删除
        java.util.Iterator<Map.Entry<Long, ProcessInfo>> iterator = activeProcesses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ProcessInfo> entry = iterator.next();
            ProcessInfo info = entry.getValue();

            // 检查是否已完成但未被移除
            if (info.finished) {
                iterator.remove();
                continue;
            }

            // 检查是否已结束
            if (!info.process.isAlive()) {
                info.finished = true;
                iterator.remove();
                continue;
            }

            // 检查是否超时运行（可能是僵尸进程）
            if (info.getRunningTime() > maxRunTime) {
                log.warn("发现僵尸进程 [id={}, command={}, running={}ms]，强制终止",
                    info.id, info.command, info.getRunningTime());
                destroyProcess(info.process, "僵尸进程清理");
                info.finished = true;
                iterator.remove();
                cleanedCount++;
            }
        }

        if (cleanedCount > 0 || !activeProcesses.isEmpty()) {
            log.debug("进程清理完成: 清理 {} 个僵尸进程, 当前活跃进程 {} 个",
                cleanedCount, activeProcesses.size());
        }
    }

    /**
     * 执行命令（带超时）
     *
     * @param command 命令及参数列表
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     * @throws IOException 执行失败时抛出
     */
    public static ProcessResult execute(List<String> command, long timeoutMs) throws IOException {
        return execute(command, null, timeoutMs);
    }

    /**
     * 执行命令（带超时和环境变量）
     *
     * @param command 命令及参数列表
     * @param env 环境变量（可选）
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     * @throws IOException 执行失败时抛出
     */
    public static ProcessResult execute(List<String> command, Map<String, String> env, long timeoutMs) throws IOException {
        return execute(command, env, timeoutMs, true);
    }

    /**
     * 执行命令（完整参数）
     *
     * @param command 命令及参数列表
     * @param env 环境变量（可选）
     * @param timeoutMs 超时时间（毫秒）
     * @param redirectErrorStream 是否合并stderr到stdout
     * @return 执行结果
     * @throws IOException 执行失败时抛出
     */
    public static ProcessResult execute(List<String> command, Map<String, String> env,
                                         long timeoutMs, boolean redirectErrorStream) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }

        String commandStr = String.join(" ", command);
        log.debug("执行命令: {}", commandStr);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(redirectErrorStream);

        // 设置环境变量
        if (env != null) {
            pb.environment().putAll(env);
        }

        long startTime = System.currentTimeMillis();
        Process process = null;
        ProcessInfo processInfo = null;

        // 进程输出读取线程
        Thread stdoutThread = null;
        Thread stderrThread = null;
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        // 用于存储输入流引用，以便在超时时关闭
        final InputStream[] stdoutStreamRef = new InputStream[1];
        final InputStream[] stderrStreamRef = new InputStream[1];

        try {
            // 启动进程
            process = pb.start();

            // 注册活跃进程
            long processId = processIdGenerator.incrementAndGet();
            processInfo = new ProcessInfo(processId, process, commandStr);
            activeProcesses.put(processId, processInfo);

            log.debug("进程已启动 [id={}, pid={}]", processId, getPid(process));

            // 异步读取stdout
            final InputStream stdoutStream = process.getInputStream();
            stdoutStreamRef[0] = stdoutStream;
            stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stdoutStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutBuilder.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.trace("读取stdout结束: {}", e.getMessage());
                }
            }, "ProcessStdout-" + processId);
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            // 异步读取stderr（如果未合并）
            if (!redirectErrorStream) {
                final InputStream stderrStream = process.getErrorStream();
                stderrStreamRef[0] = stderrStream;
                stderrThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stderrStream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stderrBuilder.append(line).append("\n");
                            // 解析进度日志
                            if (line.contains("[PROGRESS]")) {
                                log.info("[OCR] {}", line.replaceAll("\\[PROGRESS\\]\\s*", ""));
                            } else if (line.contains("[ERROR]")) {
                                log.warn("[OCR] {}", line.replaceAll("\\[ERROR\\]\\s*", ""));
                            }
                        }
                    } catch (IOException e) {
                        log.trace("读取stderr结束: {}", e.getMessage());
                    }
                }, "ProcessStderr-" + processId);
                stderrThread.setDaemon(true);
                stderrThread.start();
            }

            // 等待进程结束（带超时）
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!finished) {
                // 超时，强制终止
                String timeoutMsg = String.format("进程超时 [id=%d, timeout=%dms, command=%s]",
                    processId, timeoutMs, commandStr);
                log.warn(timeoutMsg);

                // 【修复】在终止进程前先关闭输入流，强制读取线程结束
                // 分别捕获每个流关闭的异常，避免一个失败导致另一个不关闭
                try {
                    if (stdoutStreamRef[0] != null) {
                        stdoutStreamRef[0].close();
                    }
                } catch (IOException e) {
                    log.trace("关闭 stdout 流时异常: {}", e.getMessage());
                }

                try {
                    if (stderrStreamRef[0] != null) {
                        stderrStreamRef[0].close();
                    }
                } catch (IOException e) {
                    log.trace("关闭 stderr 流时异常: {}", e.getMessage());
                }

                // 等待读取线程结束（最多等待500ms）
                joinThread(stdoutThread, 500);
                if (stderrThread != null) {
                    joinThread(stderrThread, 500);
                }

                destroyProcess(process, "超时终止");
                throw new IOException("命令执行超时（超过" + (timeoutMs / 1000) + "秒）: " + commandStr);
            }

            // 等待输出线程完成
            joinThread(stdoutThread, 1000);
            if (stderrThread != null) {
                joinThread(stderrThread, 1000);
            }

            int exitCode = process.exitValue();
            long executionTime = System.currentTimeMillis() - startTime;

            ProcessResult result = new ProcessResult(
                exitCode,
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim(),
                executionTime
            );

            if (exitCode != 0) {
                log.warn("进程执行失败 [id={}, exitCode={}, time={}ms]: {}",
                    processId, exitCode, executionTime, commandStr);
            } else {
                log.debug("进程执行成功 [id={}, time={}ms]: {}", processId, executionTime, commandStr);
            }

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("进程执行被中断", e);
        } finally {
            // 标记进程完成
            if (processInfo != null) {
                processInfo.finished = true;
                activeProcesses.remove(processInfo.id);
            }

            // 确保资源释放
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 优雅地终止进程
     */
    private static void destroyProcess(Process process, String reason) {
        if (process == null || !process.isAlive()) {
            return;
        }

        log.debug("终止进程: {}", reason);

        // 先尝试优雅终止
        process.destroy();

        try {
            // 等待最多2秒让进程自行退出
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                // 强制终止
                log.warn("进程未响应destroy()，执行destroyForcibly()");
                process.destroyForcibly();

                // 再等待1秒
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * 安全地等待线程结束
     */
    private static void joinThread(Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取进程PID（如果可用）
     */
    private static long getPid(Process process) {
        try {
            // Java 9+ 有 pid() 方法
            return process.pid();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取活跃进程数量
     */
    public static int getActiveProcessCount() {
        return (int) activeProcesses.values().stream().filter(info -> !info.finished && info.isAlive()).count();
    }

    /**
     * 获取活跃进程信息（用于监控）
     */
    public static List<Map<String, Object>> getActiveProcessInfos() {
        List<Map<String, Object>> infos = new ArrayList<>();
        for (ProcessInfo info : activeProcesses.values()) {
            if (!info.finished && info.isAlive()) {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("id", info.id);
                map.put("command", info.command);
                map.put("runningTime", info.getRunningTime());
                map.put("thread", info.threadName);
                infos.add(map);
            }
        }
        return infos;
    }

    /**
     * 强制终止所有活跃进程（用于应用关闭时清理）
     */
    public static void shutdown() {
        log.info("关闭所有活跃进程，当前数量: {}", activeProcesses.size());

        for (ProcessInfo info : activeProcesses.values()) {
            if (!info.finished && info.isAlive()) {
                log.warn("强制终止进程 [id={}, command={}]", info.id, info.command);
                destroyProcess(info.process, "应用关闭");
                info.finished = true;
            }
        }

        activeProcesses.clear();

        // 中断清理线程
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }
    }
}
