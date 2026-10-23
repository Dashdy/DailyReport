package com.welltek.backend.entity;

import java.time.LocalDateTime;

// 【用户】对应 dbo.SYS_USER
// account：登录用的账号（唯一）；username：系统里显示的名字（谁填的、谁改的都显示它）；
// password：BCrypt 密文，永远不返回给前端。三项都只能由系统管理员 / 管理员修改。
public record User(
        Integer id,
        String account,
        String username,
        String password,
        Role role,
        boolean enabled,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
