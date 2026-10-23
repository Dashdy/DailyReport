package com.welltek.backend.dto;

import java.time.LocalDate;
import java.util.List;

// 【请求体】前端 POST/PUT 过来的 JSON，按接口分组放在一起
public final class Requests {

    private Requests() {
    }

    /** 用账号登录 */
    public record Login(String account, String password) {
    }

    /** 本人修改自己的密码 */
    public record ChangePassword(String oldPassword, String newPassword) {
    }

    public record ResetPassword(String password) {
    }

    /** 新增 / 编辑用户；编辑时 password 留空表示不改密码 */
    public record SaveUser(String account, String username, String password, String role, Boolean enabled,
                           String remark, List<Integer> processIds) {
    }

    /** 系统设置：图片存储路径；migrate=true 时把旧路径下已有的图片复制过去 */
    public record SaveStorage(String uploadDir, Boolean migrate) {
    }

    /** 系统设置：数据锁定时间，HH:mm */
    /** lockDay：0 = 当天，1 = 次日 */
    public record SaveLockTime(String lockTime, Integer lockDay) {
    }

    /** 系统设置：锁定开关 / 历史数据锁定开关 */
    public record SaveLockEnabled(Boolean enabled) {
    }

    public record Ids(List<Integer> ids) {
    }

    public record SaveProcess(String name, Integer sortOrder, Boolean active) {
    }

    public record TargetItem(String metric, String baseline, String teamTarget, String unit,
                             String compareOp, Double targetValue) {
    }

    public record SaveTargets(List<TargetItem> targets) {
    }

    /** 走势表的一格；value 为 null 表示清空这一格 */
    public record Cell(LocalDate date, String metric, Double value) {
    }

    public record SaveRecords(List<Cell> cells) {
    }

    /** 班组看板：四功能小组四个人 + 全员签名 + 日期 */
    public record SaveBoard(List<BoardMember> members, String signers, LocalDate signDate) {
    }

    public record BoardMember(String photo, String name, String role) {
    }
}
