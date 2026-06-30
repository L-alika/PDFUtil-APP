package com.pdfutil.pdf.service;

import com.pdfutil.pdf.domain.PdfConvertRecord;
import java.util.List;

/**
 * PDF转换记录Service接口
 *
 * @author Alika
 * @date 2025-01-27
 */
public interface IPdfConvertRecordService {
    /**
     * 查询PDF转换记录
     *
     * @param id PDF转换记录主键
     * @return PDF转换记录
     */
    public PdfConvertRecord selectPdfConvertRecordById(Long id);

    /**
     * 查询PDF转换记录列表
     *
     * @param pdfConvertRecord PDF转换记录
     * @return PDF转换记录集合
     */
    public List<PdfConvertRecord> selectPdfConvertRecordList(PdfConvertRecord pdfConvertRecord);

    /**
     * 新增PDF转换记录
     *
     * @param pdfConvertRecord PDF转换记录
     * @return 结果
     */
    public int insertPdfConvertRecord(PdfConvertRecord pdfConvertRecord);

    /**
     * 修改PDF转换记录
     *
     * @param pdfConvertRecord PDF转换记录
     * @return 结果
     */
    public int updatePdfConvertRecord(PdfConvertRecord pdfConvertRecord);

    /**
     * 批量删除PDF转换记录
     *
     * @param ids 需要删除的PDF转换记录主键集合
     * @return 结果
     */
    public int deletePdfConvertRecordByIds(Long[] ids);

    /**
     * 删除PDF转换记录信息
     *
     * @param id PDF转换记录主键
     * @return 结果
     */
    public int deletePdfConvertRecordById(Long id);

    /**
     * 批量转换文件
     *
     * @param fileIds 文件ID列表
     * @param convertType 转换类型
     * @return 结果
     */
    public int batchConvert(Long[] fileIds, String convertType);

    /**
     * 导入转换记录
     *
     * @param records 转换记录列表
     * @return 结果
     */
    public String importRecords(List<PdfConvertRecord> records);

    /**
     * 导出转换记录
     *
     * @param records 转换记录列表
     * @return 结果
     */
    public List<PdfConvertRecord> exportRecords(PdfConvertRecord record);

    /**
     * 清空所有转换记录
     *
     * @return 清空的记录数量
     */
    public int clearAllRecords();
}
