package com.pdfutil.framework.aspectj;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.stereotype.Component;
import com.pdfutil.common.annotation.Log;
import com.pdfutil.common.core.domain.entity.SysUser;
import com.pdfutil.common.core.text.Convert;
import com.pdfutil.common.utils.ExceptionUtil;
import com.pdfutil.common.utils.ServletUtils;
import com.pdfutil.common.utils.ShiroUtils;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.framework.manager.AsyncManager;
import com.pdfutil.framework.manager.factory.AsyncFactory;

/**
 * 操作日志记录处理
 * 
 * @author Alika
 */
@Aspect
@Component
public class LogAspect
{
    private static final Logger log = LoggerFactory.getLogger(LogAspect.class);

    /** 排除敏感属性字段 */
    public static final String[] EXCLUDE_PROPERTIES = { "password", "oldPassword", "newPassword", "confirmPassword" };

    /** 计算操作消耗时间 */
    private static final ThreadLocal<Long> TIME_THREADLOCAL = new NamedThreadLocal<Long>("Cost Time");

    /** 参数最大长度限制 */
    private static final int PARAM_MAX_LENGTH = 2000;

    /**
     * 处理请求前执行
     */
    @Before(value = "@annotation(controllerLog)")
    public void doBefore(JoinPoint joinPoint, Log controllerLog)
    {
        TIME_THREADLOCAL.set(System.currentTimeMillis());
    }

    /**
     * 处理完请求后执行
     *
     * @param joinPoint 切点
     */
    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult)
    {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    /**
     * 拦截异常操作
     * 
     * @param joinPoint 切点
     * @param e 异常
     */
    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e)
    {
        handleLog(joinPoint, controllerLog, e, null);
    }

    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e, Object jsonResult)
    {
        try
        {
            // 获取当前的用户
            SysUser currentUser = ShiroUtils.getSysUser();

            // 请求的地址
            String ip = ShiroUtils.getIp();
            String requestUri = StringUtils.substring(ServletUtils.getRequest().getRequestURI(), 0, 255);
            String operName = currentUser != null ? currentUser.getLoginName() : "anonymous";

            // 设置方法名称
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            String method = className + "." + methodName + "()";

            // 设置请求方式
            String requestMethod = ServletUtils.getRequest().getMethod();

            // 计算消耗时间
            long costTime = System.currentTimeMillis() - TIME_THREADLOCAL.get();

            // 仅记录日志，不保存到数据库
            if (e != null)
            {
                log.error("[操作日志] 用户: {}, IP: {}, URI: {}, 方法: {}, 请求方式: {}, 标题: {}, 业务类型: {}, 耗时: {}ms, 状态: 失败, 错误: {}",
                    operName, ip, requestUri, method, requestMethod, controllerLog.title(), controllerLog.businessType(), costTime,
                    StringUtils.substring(Convert.toStr(e.getMessage(), ExceptionUtil.getExceptionMessage(e)), 0, 500));
            }
            else
            {
                log.info("[操作日志] 用户: {}, IP: {}, URI: {}, 方法: {}, 请求方式: {}, 标题: {}, 业务类型: {}, 耗时: {}ms, 状态: 成功",
                    operName, ip, requestUri, method, requestMethod, controllerLog.title(), controllerLog.businessType(), costTime);
            }
        }
        catch (Exception exp)
        {
            // 记录本地异常日志
            log.error("异常信息:{}", exp.getMessage());
            exp.printStackTrace();
        }
        finally
        {
            TIME_THREADLOCAL.remove();
        }
    }
}
