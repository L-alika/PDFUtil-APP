package com.pdfutil.common.core.utils;

import org.ofdrw.converter.ImageMaker;
import org.ofdrw.reader.OFDReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * OFD 单页渲染工具 - 按需渲染指定页面，避免全量渲染
 *
 * @author Alika
 * @date 2026-06-11
 */
public class OfdSinglePageRenderer {
    private static final Logger log = LoggerFactory.getLogger(OfdSinglePageRenderer.class);

    /**
     * 渲染 OFD 文档的指定页面为图片
     *
     * @param ofdPath OFD 文件路径
     * @param pageIndex 页面索引（从 0 开始）
     * @param dpi 渲染精度（DPI）
     * @return 渲染后的 BufferedImage
     * @throws Exception 渲染失败时抛出异常
     */
    public static BufferedImage renderPage(String ofdPath, int pageIndex, int dpi) throws Exception {
        log.debug("开始渲染 OFD 单页: file={}, page={}, dpi={}", ofdPath, pageIndex, dpi);

        File ofdFile = new File(ofdPath);
        if (!ofdFile.exists()) {
            throw new IllegalArgumentException("OFD 文件不存在: " + ofdPath);
        }

        try (OFDReader reader = new OFDReader(ofdFile.toPath())) {
            int pageCount = reader.getNumberOfPages();
            if (pageCount <= 0) {
                throw new IllegalArgumentException("OFD 文档没有页面");
            }

            // 修正页码范围
            pageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
            log.debug("OFD 文档总页数: {}, 请求页面: {}", pageCount, pageIndex);

            // 使用 ImageMaker 按页渲染
            // ImageMaker 构造函数参数为 DPI（每英寸毫米数 = DPI / 25.4）
            double ppm = dpi / 25.4;
            ImageMaker imageMaker = new ImageMaker(reader, ppm);

            // 只渲染指定页面
            BufferedImage image = imageMaker.makePage(pageIndex);

            if (image == null) {
                throw new RuntimeException("渲染失败，返回的图片为空");
            }

            log.debug("OFD 单页渲染成功: width={}, height={}", image.getWidth(), image.getHeight());
            return image;

        } catch (Exception e) {
            log.error("渲染 OFD 页面失败: file={}, page={}", ofdPath, pageIndex, e);
            throw e;
        }
    }

    /**
     * 渲染 OFD 文档的指定页面为图片（默认 150 DPI）
     *
     * @param ofdPath OFD 文件路径
     * @param pageIndex 页面索引（从 0 开始）
     * @return 渲染后的 BufferedImage
     * @throws Exception 渲染失败时抛出异常
     */
    public static BufferedImage renderPage(String ofdPath, int pageIndex) throws Exception {
        return renderPage(ofdPath, pageIndex, 150);
    }
}
