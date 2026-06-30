package com.pdfutil.common.core.controller;

import java.beans.PropertyEditorSupport;
import java.util.Date;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.core.domain.AjaxResult.Type;
import com.pdfutil.common.core.domain.entity.SysUser;
import com.pdfutil.common.core.page.PageDomain;
import com.pdfutil.common.core.page.TableDataInfo;
import com.pdfutil.common.core.page.TableSupport;
import com.pdfutil.common.utils.DateUtils;
import com.pdfutil.common.utils.ServletUtils;
import com.pdfutil.common.utils.ShiroUtils;
import com.pdfutil.common.utils.StringUtils;

/**
 * web层通用数据处理
 * 
 * @author Alika
 */
public class BaseController
{
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * 将前台传递过来的日期格式的字符串，自动转化为Date类型
     */
    @InitBinder
    public void initBinder(WebDataBinder binder)
    {
        // Date 类型转换
        binder.registerCustomEditor(Date.class, new PropertyEditorSupport()
        {
            @Override
            public void setAsText(String text)
            {
                setValue(DateUtils.parseDate(text));
            }
        });
    }

    /**
     * 分页参数（线程本地变量）
     */
    private static final ThreadLocal<PageDomain> PAGE_DOMAIN_HOLDER = new ThreadLocal<>();

    /**
     * 设置请求分页数据
     * 适用于JSON文件存储的内存分页
     */
    protected void startPage()
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        PAGE_DOMAIN_HOLDER.set(pageDomain);
    }

    /**
     * 获取当前分页参数
     */
    protected PageDomain getPageDomain()
    {
        return PAGE_DOMAIN_HOLDER.get();
    }

    /**
     * 清理分页的线程变量
     */
    protected void clearPage()
    {
        PAGE_DOMAIN_HOLDER.remove();
    }

    /**
     * 获取request
     */
    public HttpServletRequest getRequest()
    {
        return ServletUtils.getRequest();
    }

    /**
     * 获取response
     */
    public HttpServletResponse getResponse()
    {
        return ServletUtils.getResponse();
    }

    /**
     * 获取session
     */
    public HttpSession getSession()
    {
        return getRequest().getSession();
    }

    /**
     * 响应请求分页数据
     * 适用于JSON文件存储的内存分页
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected TableDataInfo getDataTable(List<?> list)
    {
        TableDataInfo rspData = new TableDataInfo();
        rspData.setCode(0);

        PageDomain pageDomain = PAGE_DOMAIN_HOLDER.get();
        if (pageDomain != null)
        {
            // 内存分页
            int pageNum = pageDomain.getPageNum() != null ? pageDomain.getPageNum() : 1;
            int pageSize = pageDomain.getPageSize() != null ? pageDomain.getPageSize() : 10;

            int total = list.size();
            int startIndex = (pageNum - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, total);

            if (startIndex < total)
            {
                rspData.setRows(list.subList(startIndex, endIndex));
            }
            else
            {
                rspData.setRows(java.util.Collections.emptyList());
            }
            rspData.setTotal(total);
        }
        else
        {
            // 不分页，返回全部
            rspData.setRows(list);
            rspData.setTotal(list.size());
        }

        return rspData;
    }

    /**
     * 响应返回结果
     * 
     * @param rows 影响行数
     * @return 操作结果
     */
    protected AjaxResult toAjax(int rows)
    {
        return rows > 0 ? success() : error();
    }

    /**
     * 响应返回结果
     * 
     * @param result 结果
     * @return 操作结果
     */
    protected AjaxResult toAjax(boolean result)
    {
        return result ? success() : error();
    }

    /**
     * 返回成功
     */
    public AjaxResult success()
    {
        return AjaxResult.success();
    }

    /**
     * 返回失败消息
     */
    public AjaxResult error()
    {
        return AjaxResult.error();
    }

    /**
     * 返回成功消息
     */
    public AjaxResult success(String message)
    {
        return AjaxResult.success(message);
    }

    /**
     * 返回成功数据
     */
    public static AjaxResult success(Object data)
    {
        return AjaxResult.success("操作成功", data);
    }

    /**
     * 返回失败消息
     */
    public AjaxResult error(String message)
    {
        return AjaxResult.error(message);
    }

    /**
     * 返回错误码消息
     */
    public AjaxResult error(Type type, String message)
    {
        return new AjaxResult(type, message);
    }

    /**
     * 页面跳转
     */
    public String redirect(String url)
    {
        return StringUtils.format("redirect:{}", url);
    }

    /**
     * 获取用户缓存信息
     */
    public SysUser getSysUser()
    {
        return ShiroUtils.getSysUser();
    }

    /**
     * 设置用户缓存信息
     */
    public void setSysUser(SysUser user)
    {
        ShiroUtils.setSysUser(user);
    }

    /**
     * 获取登录用户id
     */
    public Long getUserId()
    {
        return getSysUser().getUserId();
    }

    /**
     * 获取登录用户名
     */
    public String getLoginName()
    {
        return getSysUser().getLoginName();
    }
}
