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
                           String remark, List<Integer> processIds, String wecomUserId) {
    }

    /** 系统设置：图片存储路径；migrate=true 时把旧路径下已有的图片复制过去 */
    public record SaveStorage(String uploadDir, Boolean migrate) {
    }

    /** 系统设置：数据锁定时间 HH:mm；lockDay：0 = 当天，1 = 次日 */
    public record SaveLockTime(String lockTime, Integer lockDay) {
    }

    /**
     * 系统设置：企业微信推送。
     * webhook 传回打码后的样子（含 ****）表示不改，传空字符串表示清除。
     */
    public record SavePush(Boolean enabled, String time, String title, List<Integer> receivers, String webhook) {
    }

    /** 系统设置：锁定开关 / 历史数据锁定开关 */
    public record SaveLockEnabled(Boolean enabled) {
    }

    public record Ids(List<Integer> ids) {
    }

    public record SaveProcess(String name, Integer sortOrder, Boolean active) {
    }

    /** 保证书的一项：公司底线 / 班组自定 + 这项的子项（至少一个） */
    public record TargetItem(String metric, String baseline, String teamTarget, List<SubItem> items) {
    }

    /** 子项：id 为空表示新加的；原来有、这次没传的子项会被删除（数据保留） */
    public record SubItem(Integer id, String name, String unit, String compareOp, Double targetValue) {
    }

    public record SaveTargets(List<TargetItem> targets) {
    }

    /** 走势表的一格（某天某个子项）；value 为 null 表示清空这一格 */
    public record Cell(LocalDate date, Integer itemId, Double value) {
    }

    public record SaveRecords(List<Cell> cells) {
    }

    /** 班组看板：四功能小组四个人（全员签名、日期 2026-10-24 起不再使用，库里的老数据保留） */
    public record SaveBoard(List<BoardMember> members) {
    }

    /** 某天的 今日行动措施 / 今日亮点 / 明日行动计划 */
    public record SaveNote(LocalDate date, String actions, String highlights, String plans) {
    }

    public record BoardMember(String photo, String name, String role) {
    }
}
