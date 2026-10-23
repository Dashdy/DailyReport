package com.welltek.backend.entity;

// 【权限等级】系统管理员 > 管理员 > 用户
// 系统管理员、管理员：可以管理用户、分配工序负责人、编辑所有工序的数据；
// 用户：只能编辑分配给自己的工序，其余工序只读。
public enum Role {
    SYS_ADMIN("系统管理员"),
    ADMIN("管理员"),
    USER("用户");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isAdmin() {
        return this == SYS_ADMIN || this == ADMIN;
    }
}
