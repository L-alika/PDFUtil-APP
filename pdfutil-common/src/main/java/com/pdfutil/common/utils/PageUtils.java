package com.pdfutil.common.utils;

import com.pdfutil.common.core.page.PageDomain;
import com.pdfutil.common.core.page.TableSupport;

/**
 * 分页工具类
 * 适用于JSON文件存储的内存分页（无数据库依赖）
 *
 * @author Alika
 */
public class PageUtils
{
    /**
     * 分页参数（线程本地变量）
     */
    private static final ThreadLocal<PageDomain> PAGE_DOMAIN_HOLDER = new ThreadLocal<>();

    /**
     * 设置请求分页数据
     */
    public static void startPage()
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        PAGE_DOMAIN_HOLDER.set(pageDomain);
    }

    /**
     * 获取当前分页参数
     */
    public static PageDomain getPageDomain()
    {
        return PAGE_DOMAIN_HOLDER.get();
    }

    /**
     * 清理分页的线程变量
     */
    public static void clearPage()
    {
        PAGE_DOMAIN_HOLDER.remove();
    }
}
