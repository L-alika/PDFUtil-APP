package com.pdfutil.pdf.controller;

import com.pdfutil.common.core.controller.BaseController;
import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.core.page.TableDataInfo;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import com.pdfutil.pdf.service.IPdfConvertRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PDF转换记录Controller
 * 使用JSON文件存储
 *
 * @author Alika
 * @date 2025-01-27
 */
@RestController
@RequestMapping("/pdf/record")
public class PdfConvertRecordController extends BaseController {
    @Autowired
    private IPdfConvertRecordService pdfConvertRecordService;

    /**
     * 查询PDF转换记录列表
     */
    @GetMapping("/list")
    public TableDataInfo list(PdfConvertRecord pdfConvertRecord) {
        startPage();
        List<PdfConvertRecord> list = pdfConvertRecordService.selectPdfConvertRecordList(pdfConvertRecord);
        return getDataTable(list);
    }

    /**
     * 获取PDF转换记录详细信息
     */
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(pdfConvertRecordService.selectPdfConvertRecordById(id));
    }

    /**
     * 新增PDF转换记录
     */
    @PostMapping
    public AjaxResult add(@RequestBody PdfConvertRecord pdfConvertRecord) {
        return toAjax(pdfConvertRecordService.insertPdfConvertRecord(pdfConvertRecord));
    }

    /**
     * 修改PDF转换记录
     */
    @PutMapping
    public AjaxResult edit(@RequestBody PdfConvertRecord pdfConvertRecord) {
        return toAjax(pdfConvertRecordService.updatePdfConvertRecord(pdfConvertRecord));
    }

    /**
     * 删除PDF转换记录
     */
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(pdfConvertRecordService.deletePdfConvertRecordByIds(ids));
    }

    /**
     * 清空所有PDF转换记录
     */
    @DeleteMapping("/clear")
    public AjaxResult clearAll() {
        int count = pdfConvertRecordService.clearAllRecords();
        return success("成功清空 " + count + " 条记录");
    }
}