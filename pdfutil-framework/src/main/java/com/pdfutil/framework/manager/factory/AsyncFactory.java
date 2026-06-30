package com.pdfutil.framework.manager.factory;

import java.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pdfutil.common.utils.AddressUtils;
import com.pdfutil.common.utils.LogUtils;
import com.pdfutil.common.utils.ServletUtils;
import com.pdfutil.common.utils.ShiroUtils;
import com.pdfutil.common.utils.http.UserAgentUtils;

/**
 * 异步工厂（产生任务用）
 * 简化版 - 仅保留日志记录功能，不依赖数据库
 *
 * @author liuhulu
 * @author Alika
 */
public class AsyncFactory
{
    private static final Logger sys_user_logger = LoggerFactory.getLogger("sys-user");

    /**
     * 记录登录信息（仅日志，不写入数据库）
     *
     * @param username 用户名
     * @param status 状态
     * @param message 消息
     * @param args 列表
     * @return 任务task
     */
    public static TimerTask recordLogininfor(final String username, final String status, final String message, final Object... args)
    {
        final String userAgent = ServletUtils.getRequest().getHeader("User-Agent");
        final String ip = ShiroUtils.getIp();
        return new TimerTask()
        {
            @Override
            public void run()
            {
                String address = AddressUtils.getRealAddressByIP(ip);
                StringBuilder s = new StringBuilder();
                s.append(LogUtils.getBlock(ip));
                s.append(address);
                s.append(LogUtils.getBlock(username));
                s.append(LogUtils.getBlock(status));
                s.append(LogUtils.getBlock(message));
                // 打印信息到日志
                sys_user_logger.info(s.toString(), args);
                // 获取客户端操作系统
                String os = UserAgentUtils.getOperatingSystem(userAgent);
                // 获取客户端浏览器
                String browser = UserAgentUtils.getBrowser(userAgent);
                // 仅记录日志，不写入数据库
                sys_user_logger.info("登录信息 - 用户: {}, IP: {}, 地址: {}, 系统: {}, 浏览器: {}",
                    username, ip, address, os, browser);
            }
        };
    }
}
