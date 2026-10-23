package com.welltek.backend.controller;

import com.welltek.backend.dto.ApiResult;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.security.AuthInterceptor;
import com.welltek.backend.security.LoginUser;
import com.welltek.backend.security.RequireAdmin;
import com.welltek.backend.service.AuditService;
import com.welltek.backend.service.ProcessService;
import com.welltek.backend.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;

// 【工序】选项卡列表、工序详情（保证书 + 走势表 + 班组看板）、工序配置与负责人分配
@RestController
@RequestMapping("/api/processes")
public class ProcessController {

    @Autowired
    private ProcessService processService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private AuditService auditService;

    /** 选项卡页：所有启用的工序 + 指定日期（默认今天）的填报情况 */
    @GetMapping
    public ApiResult cards(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok("ok", processService.listCards(op, date == null ? LocalDate.now() : date));
    }

    /** 工序配置 / 工序分配页：全部工序（含停用）+ 负责人 */
    @RequireAdmin
    @GetMapping("/all")
    public ApiResult all() {
        return ApiResult.ok("ok", processService.listAll());
    }

    @RequireAdmin
    @PostMapping
    public ApiResult create(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                            @RequestBody Requests.SaveProcess req) {
        processService.create(op, req);
        return ApiResult.ok("工序已新增");
    }

    @RequireAdmin
    @PutMapping("/{id}")
    public ApiResult update(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                            @PathVariable Integer id, @RequestBody Requests.SaveProcess req) {
        processService.update(op, id, req);
        return ApiResult.ok("工序已保存");
    }

    @RequireAdmin
    @PutMapping("/{id}/owners")
    public ApiResult setOwners(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                               @PathVariable Integer id, @RequestBody Requests.Ids req) {
        processService.setOwners(op, id, req.ids());
        return ApiResult.ok("负责人已保存");
    }

    // ---------------- 工序详情 ----------------

    /** month 格式 yyyy-MM，默认当月 */
    @GetMapping("/{id}/detail")
    public ApiResult detail(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                            @PathVariable Integer id, @RequestParam(required = false) String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        return ApiResult.ok("ok", reportService.detail(op, id, ym));
    }

    @PutMapping("/{id}/records")
    public ApiResult saveRecords(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                 @PathVariable Integer id, @RequestBody Requests.SaveRecords req) {
        int changed = reportService.saveRecords(op, id, req.cells());
        return ApiResult.ok(changed == 0 ? "没有需要保存的修改" : "已保存 " + changed + " 处修改", changed);
    }

    @PutMapping("/{id}/targets")
    public ApiResult saveTargets(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                 @PathVariable Integer id, @RequestBody Requests.SaveTargets req) {
        reportService.saveTargets(op, id, req.targets());
        return ApiResult.ok("六项目标已保存");
    }

    @PutMapping("/{id}/board")
    public ApiResult saveBoard(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                               @PathVariable Integer id, @RequestBody Requests.SaveBoard req) {
        reportService.saveBoard(op, id, req);
        return ApiResult.ok("班组看板已保存");
    }

    /** 这道工序的修改记录（填数 / 目标 / 看板），登录用户都能看 */
    @GetMapping("/{id}/logs")
    public ApiResult logs(@PathVariable Integer id,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "15") int size) {
        return ApiResult.ok("ok", auditService.processPage(processService.requireProcess(id).name(), page, size));
    }

    /** 某天的战报留底版本 */
    @GetMapping("/{id}/snapshots")
    public ApiResult snapshots(@PathVariable Integer id,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok("ok", reportService.snapshots(id, date));
    }
}
