package com.welltek.backend.controller;

import com.welltek.backend.dto.ApiResult;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.security.AuthInterceptor;
import com.welltek.backend.security.LoginUser;
import com.welltek.backend.security.RequireAdmin;
import com.welltek.backend.security.RequireSysAdmin;
import com.welltek.backend.service.PushService;
import com.welltek.backend.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// 【系统设置】系统管理员、管理员可以进来查看，普通用户不行。
// - 图片存储路径：系统管理员、管理员都能改；
// - 数据锁定时间：只有系统管理员能改，管理员只能看；
// - 锁定开关、历史数据锁定开关：系统管理员、管理员都能拨，随时卡控；
// - 企业微信推送：系统管理员、管理员可以配置、发测试消息、立即执行一次自动推送。
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    @Autowired
    private SettingService settingService;

    @Autowired
    private PushService pushService;

    // ---------------- 企业微信推送 ----------------

    @RequireAdmin
    @GetMapping("/push")
    public ApiResult push() {
        return ApiResult.ok("ok", pushService.settings());
    }

    @RequireAdmin
    @PutMapping("/push")
    public ApiResult savePush(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                              @RequestBody Requests.SavePush req) {
        pushService.saveSettings(op, req);
        return ApiResult.ok("推送设置已保存", pushService.settings());
    }

    @RequireAdmin
    @PostMapping("/push/test")
    public ApiResult pushTest(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op) {
        return ApiResult.ok(pushService.sendTest(op), pushService.settings());
    }

    @RequireAdmin
    @PostMapping("/push/run")
    public ApiResult pushRun(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op) {
        pushService.runAutoPushNow(op);
        return ApiResult.ok("已开始推送今天的战报，工序多的话要一两分钟，稍后刷新看推送记录");
    }

    @RequireAdmin
    @GetMapping("/storage")
    public ApiResult storage() {
        return ApiResult.ok("ok", settingService.storageInfo());
    }

    @RequireAdmin
    @PutMapping("/storage")
    public ApiResult saveStorage(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                 @RequestBody Requests.SaveStorage req) {
        String message = settingService.saveUploadDir(op, req.uploadDir(), Boolean.TRUE.equals(req.migrate()));
        return ApiResult.ok(message, settingService.storageInfo());
    }

    @RequireAdmin
    @GetMapping("/lock")
    public ApiResult lock() {
        return ApiResult.ok("ok", settingService.lockInfo());
    }

    @RequireSysAdmin
    @PutMapping("/lock")
    public ApiResult saveLock(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                              @RequestBody Requests.SaveLockTime req) {
        settingService.saveLockTime(op, req.lockTime(), req.lockDay());
        return ApiResult.ok("锁定时间已保存", settingService.lockInfo());
    }

    @RequireAdmin
    @PutMapping("/lock/enabled")
    public ApiResult saveLockEnabled(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                     @RequestBody Requests.SaveLockEnabled req) {
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        settingService.saveLockEnabled(op, enabled);
        return ApiResult.ok(enabled ? "已开启锁定" : "已关闭锁定，负责人当天全天都能填写", settingService.lockInfo());
    }

    @RequireAdmin
    @PutMapping("/lock/history")
    public ApiResult saveHistoryLock(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                     @RequestBody Requests.SaveLockEnabled req) {
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        settingService.saveHistoryLock(op, enabled);
        return ApiResult.ok(enabled ? "已开启历史数据锁定" : "已关闭历史数据锁定，负责人可以修改以前的数据", settingService.lockInfo());
    }
}
