package com.welltek.backend.controller;

import com.welltek.backend.dto.ApiResult;
import com.welltek.backend.security.RequireAdmin;
import com.welltek.backend.service.AuditService;
import com.welltek.backend.service.DashboardService;
import com.welltek.backend.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Map;

// 【首页汇总 / 操作日志 / 图片上传】首页和上传登录用户都能用，操作日志仅系统管理员 / 管理员
@RestController
@RequestMapping("/api")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private FileService fileService;

    /** 首页：全厂六项指标达标汇总，date 默认今天 */
    @GetMapping("/dashboard")
    public ApiResult dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok("ok", dashboardService.summary(date == null ? LocalDate.now() : date));
    }

    /** 操作日志（属于系统管理，仅系统管理员 / 管理员）：分页 + 按模块 / 工序 / 操作人 / 日期筛选 */
    @RequireAdmin
    @GetMapping("/audit-logs")
    public ApiResult auditLogs(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String module,
                               @RequestParam(required = false) String processName,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResult.ok("ok", auditService.page(page, size, module, processName, keyword, startDate, endDate));
    }

    @PostMapping("/files")
    public ApiResult upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok("上传成功", Map.of("url", fileService.saveImage(file)));
    }
}
