package com.pdfutil.pdf.service.impl;

import com.pdfutil.common.utils.DateUtils;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import com.pdfutil.pdf.utils.JsonRecordStorage;
import com.pdfutil.pdf.service.IPdfConvertRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PDF转换记录Service业务层处理
 * 使用JSON文件存储替代数据库存储
 *
 * @author Alika
 * @date 2025-01-27
 */
@Service
public class PdfConvertRecordServiceImpl implements IPdfConvertRecordService {

    @Autowired
    private JsonRecordStorage jsonStorage;

    /**
     * 查询PDF转换记录
     *
     * @param id PDF转换记录主键
     * @return PDF转换记录
     */
    @Override
    public PdfConvertRecord selectPdfConvertRecordById(Long id) {
        return jsonStorage.findById(id);
    }

    /**
     * 查询PDF转换记录列表
     *
     * @param pdfConvertRecord PDF转换记录
     * @return PDF转换记录
     */
    @Override
    public List<PdfConvertRecord> selectPdfConvertRecordList(PdfConvertRecord pdfConvertRecord) {
        return jsonStorage.findList(pdfConvertRecord);
    }

    /**
     * 新增PDF转换记录
     *
     * @param pdfConvertRecord PDF转换记录
     * @return 结果
     */
    @Override
    public int insertPdfConvertRecord(PdfConvertRecord pdfConvertRecord) {
        pdfConvertRecord.setCreateTime(DateUtils.getNowDate());
        if (pdfConvertRecord.getId() == null) {
            pdfConvertRecord.setId(jsonStorage.generateId());
        }
        return jsonStorage.insert(pdfConvertRecord);
    }

    /**
     * 修改PDF转换记录
     *
     * @param pdfConvertRecord PDF转换记录
     * @return 结果
     */
    @Override
    public int updatePdfConvertRecord(PdfConvertRecord pdfConvertRecord) {
        pdfConvertRecord.setUpdateTime(DateUtils.getNowDate());
        return jsonStorage.update(pdfConvertRecord);
    }

    /**
     * 批量删除PDF转换记录
     *
     * @param ids 需要删除的PDF转换记录主键
     * @return 结果
     */
    @Override
    public int deletePdfConvertRecordByIds(Long[] ids) {
        return jsonStorage.deleteByIds(ids);
    }

    /**
     * 删除PDF转换记录信息
     *
     * @param id PDF转换记录主键
     * @return 结果
     */
    @Override
    public int deletePdfConvertRecordById(Long id) {
        return jsonStorage.deleteById(id);
    }

    /**
     * 批量转换文件
     *
     * @param fileIds 文件ID列表
     * @param convertType 转换类型
     * @return 结果
     */
    @Override
    public int batchConvert(Long[] fileIds, String convertType) {
        // TODO: 实现批量转换逻辑
        return 0;
    }

    /**
     * 导入转换记录
     *
     * @param records 转换记录列表
     * @return 结果
     */
    @Override
    public String importRecords(List<PdfConvertRecord> records) {
        if (StringUtils.isNull(records) || records.size() == 0) {
            return "导入数据不能为空";
        }
        int successNum = 0;
        int failureNum = 0;
        StringBuilder successMsg = new StringBuilder();
        StringBuilder failureMsg = new StringBuilder();

        for (PdfConvertRecord record : records) {
            try {
                // 插入记录
                int rows = insertPdfConvertRecord(record);
                if (rows > 0) {
                    successNum++;
                    successMsg.append("<br/>").append(successNum).append("、文件 ").append(record.getSourceFileName()).append(" 导入成功");
                } else {
                    failureNum++;
                    failureMsg.append("<br/>").append(failureNum).append("、文件 ").append(record.getSourceFileName()).append(" 导入失败");
                }
            } catch (Exception e) {
                failureNum++;
                String msg = "<br/>" + failureNum + "、文件 " + record.getSourceFileName() + " 导入失败：";
                failureMsg.append(msg).append(e.getMessage());
            }
        }

        if (failureNum > 0) {
            failureMsg.insert(0, "很抱歉，导入失败！共 " + failureNum + " 条数据格式不正确，错误如下：");
            throw new RuntimeException(failureMsg.toString());
        } else {
            successMsg.insert(0, "恭喜您，数据已全部导入成功！共 " + successNum + " 条");
        }

        return successMsg.toString();
    }

    /**
     * 导出转换记录
     *
     * @param record 转换记录查询条件
     * @return 转换记录列表
     */
    @Override
    public List<PdfConvertRecord> exportRecords(PdfConvertRecord record) {
        return jsonStorage.findList(record);
    }

    /**
     * 清空所有转换记录
     *
     * @return 清空的记录数量
     */
    @Override
    public int clearAllRecords() {
        return jsonStorage.clearAll();
    }
}