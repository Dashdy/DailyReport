package com.welltek.backend.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 【接口权限注解】只有系统管理员能调用（如系统设置），管理员和用户调用都返回 403。
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireSysAdmin {
}
