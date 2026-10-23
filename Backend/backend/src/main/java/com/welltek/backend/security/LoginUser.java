package com.welltek.backend.security;

import com.welltek.backend.entity.Role;

import java.util.Set;

// 【当前登录用户】AuthInterceptor 每次请求都从数据库重新查一份放进 request 属性里，
// 所以管理员刚改完的角色 / 负责工序，用户下一次请求就生效，不用重新登录。
public record LoginUser(Integer id, String account, String username, Role role, Set<Integer> processIds) {

    public boolean isAdmin() {
        return role.isAdmin();
    }

    public boolean isSysAdmin() {
        return role == Role.SYS_ADMIN;
    }

    /** 能不能编辑某道工序的数据：管理员都能编辑，普通用户只能编辑自己负责的工序 */
    public boolean canEdit(Integer processId) {
        return isAdmin() || processIds.contains(processId);
    }
}
