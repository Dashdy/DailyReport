package com.welltek.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.welltek.backend.dto.ApiResult;
import com.welltek.backend.entity.User;
import com.welltek.backend.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

// 【权限拦截器】所有 /api/** 接口（登录除外）先经过这里：
// 1. 校验请求头 Authorization: Bearer <token>，无效返回 401；
// 2. 按 Token 里的用户 ID 重新查一份最新的用户和负责工序，停用的账号返回 401；
// 3. 方法上标了 @RequireAdmin / @RequireSysAdmin 而权限不够，返回 403；
// 4. 通过后把 LoginUser 放进 request 属性，Controller 用 @RequestAttribute 取。
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTR = "currentUser";

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // 跨域预检请求直接放行
        }

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;

        Integer userId = tokenStore.validate(token);
        if (userId == null) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期，请重新登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null || !user.enabled()) {
            tokenStore.invalidate(token);
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "账号不存在或已停用，请联系管理员");
        }

        LoginUser loginUser = new LoginUser(user.id(), user.account(), user.username(), user.role(), userMapper.selectProcessIds(user.id()));

        if (handler instanceof HandlerMethod method) {
            if (method.hasMethodAnnotation(RequireSysAdmin.class) && !loginUser.isSysAdmin()) {
                return reject(response, HttpServletResponse.SC_FORBIDDEN, "权限不足：只有系统管理员可以操作");
            }
            if (method.hasMethodAnnotation(RequireAdmin.class) && !loginUser.isAdmin()) {
                return reject(response, HttpServletResponse.SC_FORBIDDEN, "权限不足：只有系统管理员和管理员可以操作");
            }
        }

        request.setAttribute(CURRENT_USER_ATTR, loginUser);
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.fail(message)));
        return false;
    }
}
