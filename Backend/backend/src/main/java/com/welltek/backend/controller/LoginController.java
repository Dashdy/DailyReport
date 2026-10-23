package com.welltek.backend.controller;

import com.welltek.backend.dto.ApiResult;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.entity.User;
import com.welltek.backend.mapper.UserMapper;
import com.welltek.backend.security.AuthInterceptor;
import com.welltek.backend.security.LoginUser;
import com.welltek.backend.security.TokenStore;
import com.welltek.backend.service.AuditService;
import com.welltek.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// 【登录 / 登出 / 当前用户 / 修改自己的密码】
// 用账号 + 密码登录；用户名、账号由系统管理员 / 管理员在“用户管理”里修改，密码本人也可以改。
@RestController
@RequestMapping("/api")
public class LoginController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditService auditService;

    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public ApiResult login(@RequestBody Requests.Login req) {
        String account = req.account() == null ? "" : req.account().trim();
        User user = userMapper.selectByAccount(account);
        if (user == null || req.password() == null || !passwordEncoder.matches(req.password(), user.password())) {
            return ApiResult.fail("账号或密码错误");
        }
        if (!user.enabled()) {
            return ApiResult.fail("账号已停用，请联系管理员");
        }

        String token = tokenStore.issueToken(user.id());
        LoginUser loginUser = new LoginUser(user.id(), user.account(), user.username(), user.role(), userMapper.selectProcessIds(user.id()));
        auditService.log(loginUser, AuditService.MODULE_LOGIN, "登录", null, "用户 " + user.username() + "（" + user.account() + "）", null, null);

        Map<String, Object> data = profile(loginUser);
        data.put("token", token);
        return ApiResult.ok("登录成功", data);
    }

    @PostMapping("/logout")
    public ApiResult logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokenStore.invalidate(header.substring(7));
        }
        return ApiResult.ok("已退出登录");
    }

    /** 前端刷新页面时拉一次最新的用户名、角色和负责工序 */
    @GetMapping("/me")
    public ApiResult me(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op) {
        return ApiResult.ok("ok", profile(op));
    }

    @PutMapping("/me/password")
    public ApiResult changePassword(@RequestAttribute(AuthInterceptor.CURRENT_USER_ATTR) LoginUser op,
                                    @RequestBody Requests.ChangePassword req) {
        userService.changeOwnPassword(op, req.oldPassword(), req.newPassword());
        return ApiResult.ok("密码已修改");
    }

    private static Map<String, Object> profile(LoginUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("account", u.account());
        m.put("username", u.username());
        m.put("role", u.role().name());
        m.put("roleLabel", u.role().getLabel());
        m.put("processIds", u.processIds());
        return m;
    }
}
