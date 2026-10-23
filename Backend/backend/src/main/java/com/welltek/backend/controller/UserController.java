package com.welltek.backend.controller;

import com.welltek.backend.dto.ApiResult;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.security.AuthInterceptor;
import com.welltek.backend.security.LoginUser;
import com.welltek.backend.security.RequireAdmin;
import com.welltek.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// 【用户管理】只有系统管理员和管理员可以访问
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @RequireAdmin
    @GetMapping
    public ApiResult list() {
        return ApiResult.ok("ok", userService.list());
    }

    @RequireAdmin
    @PostMapping
    public ApiResult create(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                            @RequestBody Requests.SaveUser req) {
        userService.create(op, req);
        return ApiResult.ok("用户已创建");
    }

    @RequireAdmin
    @PutMapping("/{id}")
    public ApiResult update(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                            @PathVariable Integer id, @RequestBody Requests.SaveUser req) {
        userService.update(op, id, req);
        return ApiResult.ok("用户已保存");
    }

    @RequireAdmin
    @DeleteMapping("/{id}")
    public ApiResult delete(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                            @PathVariable Integer id) {
        userService.delete(op, id);
        return ApiResult.ok("用户已删除");
    }

    @RequireAdmin
    @PutMapping("/{id}/password")
    public ApiResult resetPassword(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                   @PathVariable Integer id, @RequestBody Requests.ResetPassword req) {
        userService.resetPassword(op, id, req.password());
        return ApiResult.ok("密码已重置，该用户需要重新登录");
    }

    @RequireAdmin
    @PutMapping("/{id}/processes")
    public ApiResult assignProcesses(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                     @PathVariable Integer id, @RequestBody Requests.Ids req) {
        userService.assignProcesses(op, id, req.ids());
        return ApiResult.ok("负责工序已保存");
    }
}
