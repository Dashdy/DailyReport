package com.welltek.backend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 【接口权限注解】标在 Controller 方法上，表示只有系统管理员 / 管理员能调用，
// 普通用户调用会被 AuthInterceptor 直接返回 403。
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireAdmin {
}
