package com.welltek.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.welltek.backend.mapper.AuditMapper;
import com.welltek.backend.security.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 【操作日志】所有改数据的地方都调用 log()，记录：谁、哪个模块、做了什么、原数据、新数据
@Service
public class AuditService {

    public static final String MODULE_LOGIN = "登录";
    public static final String MODULE_USER = "用户管理";
    public static final String MODULE_ASSIGN = "工序分配";
    public static final String MODULE_PROCESS = "工序配置";
    public static final String MODULE_RECORD = "每日数据";
    public static final String MODULE_TARGET = "目标设定";
    public static final String MODULE_BOARD = "班组看板";
    public static final String MODULE_NOTE = "行动与亮点";
    public static final String MODULE_PUSH = "企业微信推送";

    @Autowired
    private AuditMapper auditMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /** before / after 传字符串就原样存；传 Map 等对象就转成 JSON 存 */
    public void log(LoginUser operator, String module, String action, String processName,
                    String target, Object before, Object after) {
        auditMapper.insert(operator == null ? null : operator.id(),
                operator == null ? null : operator.username(),
                module, action, processName, target, toText(before), toText(after));
    }

    /** 操作日志页（系统管理，仅系统管理员 / 管理员）：全部模块 */
    public Map<String, Object> page(int page, int size, String module, String processName,
                                    String keyword, LocalDate startDate, LocalDate endDate) {
        return auditMapper.selectPage(page, clampSize(size), null, module, processName, keyword, startDate, endDate);
    }

    /** 工序详情页的修改记录（所有登录用户）：只有这道工序的填数、目标、看板、行动与亮点记录，不含用户管理、负责人分配等系统管理信息 */
    public Map<String, Object> processPage(String processName, int page, int size) {
        return auditMapper.selectPage(page, clampSize(size), List.of(MODULE_RECORD, MODULE_TARGET, MODULE_BOARD, MODULE_NOTE),
                null, processName, null, null, null);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 200);
    }

    /** 数值统一成最简写法：3.10 → 3.1，320.0 → 320 */
    public static String formatNumber(Double v) {
        return v == null ? "" : BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
