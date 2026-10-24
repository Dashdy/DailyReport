package com.welltek.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.welltek.backend.config.BizException;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.entity.*;
import com.welltek.backend.mapper.*;
import com.welltek.backend.security.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

// 【工序详情：六项自主保证书 + 每日走势表 + 班组看板】
// 填报规则：未来日期谁都不能填；工序负责人只能在当天锁定时间（系统设置里定义）之前填当天的数据，
// 以前的和过了锁定时间的数据只有系统管理员 / 管理员能更正。
// 留存规则：
// 1. 每一格的新增 / 修改 / 清空都写一条操作日志（操作人、原值、新值）；
// 2. 每格记录首次填报人和最后保存人；
// 3. 每次保存后，被改动的那几天各存一份完整战报快照（DAILY_SNAPSHOT），版本号递增，只增不改。
@Service
public class ReportService {

    @Autowired
    private ProcessService processService;

    @Autowired
    private TargetMapper targetMapper;

    @Autowired
    private RecordMapper recordMapper;

    @Autowired
    private BoardMapper boardMapper;

    @Autowired
    private SnapshotMapper snapshotMapper;

    @Autowired
    private ProcessMapper processMapper;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SettingService settingService;

    public Map<String, Object> detail(LoginUser op, Integer processId, YearMonth month) {
        ProcessStep p = processService.requireProcess(processId);
        Map<Metric, MetricTarget> targets = targetMapper.selectByProcess(processId);

        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> targetList = new ArrayList<>();
        for (Metric m : Metric.values()) {
            metrics.add(Map.of("key", m.name(), "label", m.getLabel()));
            MetricTarget t = targets.get(m);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("metric", m.name());
            tm.put("label", m.getLabel());
            tm.put("baseline", t == null ? null : t.baseline());
            tm.put("teamTarget", t == null ? null : t.teamTarget());
            tm.put("unit", t == null ? null : t.unit());
            tm.put("compareOp", t == null ? "LE" : t.compareOp());
            tm.put("targetValue", t == null ? null : t.targetValue());
            tm.put("updatedBy", t == null ? null : t.updatedBy());
            tm.put("updatedAt", t == null ? null : t.updatedAt());
            targetList.add(tm);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (DailyRecord r : recordMapper.selectByProcess(processId, month.atDay(1), month.atEndOfMonth())) {
            MetricTarget t = targets.get(r.metric());
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("date", r.reportDate());
            rm.put("metric", r.metric().name());
            rm.put("value", r.value());
            rm.put("status", MetricStatus.judge(r.value(), t == null ? null : t.compareOp(), t == null ? null : t.targetValue()));
            rm.put("createdBy", r.createdBy());
            rm.put("createdAt", r.createdAt());
            rm.put("updatedBy", r.updatedBy());
            rm.put("updatedAt", r.updatedAt());
            records.add(rm);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("process", Map.of("id", p.id(), "name", p.name(), "active", p.active()));
        result.put("owners", processMapper.selectOwners().getOrDefault(processId, List.of()));
        // 停用的工序只读：历史数据照样能看，谁都不能再改（要改先在“工序配置”里重新启用）
        result.put("canEdit", p.active() && op.canEdit(processId));
        // 以服务器时间为准，告诉前端负责人现在能改哪几天（规则见 SettingService.ownerCanEdit）；管理员不受限制
        result.put("today", LocalDate.now());
        result.put("canEditPast", op.isAdmin());
        result.put("historyLock", settingService.historyLockEnabled());
        result.put("ownerTodayEditable", settingService.ownerTodayEditable());
        result.put("ownerYesterdayEditable", settingService.ownerYesterdayEditable());
        result.put("lockTime", settingService.lockInfo().get("lockTime"));
        result.put("lockRule", settingService.lockRuleText());
        result.put("lockDay", settingService.lockDayOffset());
        result.put("lockEnabled", settingService.lockEnabled());
        result.put("month", month.toString());
        result.put("daysInMonth", month.lengthOfMonth());
        result.put("metrics", metrics);
        result.put("targets", targetList);
        result.put("records", records);
        result.put("board", boardMapper.selectByProcess(processId));
        return result;
    }

    /** 保存走势表：只处理有变化的格子，返回实际改动了几格 */
    @Transactional
    public int saveRecords(LoginUser op, Integer processId, List<Requests.Cell> cells) {
        ProcessStep p = processService.requireProcess(processId);
        checkCanEdit(op, p);
        if (cells == null || cells.isEmpty()) {
            return 0;
        }

        LocalDate today = LocalDate.now();
        String lockRule = settingService.lockRuleText();
        Set<LocalDate> changedDates = new TreeSet<>();
        int changed = 0;
        for (Requests.Cell c : cells) {
            if (c.date() == null || c.metric() == null) {
                continue;
            }
            if (c.date().isAfter(today)) {
                throw new BizException("不能填写未来日期（" + c.date() + "）的数据");
            }
            // 工序负责人受“系统设置 → 数据锁定”的规则限制（历史数据锁定、当天 / 次日锁定时间）；
            // 系统管理员 / 管理员不受限制（会记操作日志）。
            if (!op.isAdmin() && !settingService.ownerCanEdit(c.date())) {
                throw new BizException(c.date().equals(today)
                        ? "今天的数据已按“" + lockRule + "”锁定，需要修改请联系管理员"
                        : c.date() + " 的数据已锁定，需要更正请联系管理员");
            }
            Metric metric;
            try {
                metric = Metric.valueOf(c.metric());
            } catch (IllegalArgumentException e) {
                throw new BizException("未知的指标：" + c.metric());
            }
            String target = c.date() + " " + metric.getLabel();
            DailyRecord old = recordMapper.selectOne(processId, c.date(), metric);

            if (c.value() == null) {
                if (old != null) {
                    recordMapper.delete(old.id());
                    auditService.log(op, AuditService.MODULE_RECORD, "清空", p.name(), target,
                            AuditService.formatNumber(old.value()), null);
                    changedDates.add(c.date());
                    changed++;
                }
            } else if (old == null) {
                recordMapper.insert(processId, c.date(), metric, c.value(), op.username());
                auditService.log(op, AuditService.MODULE_RECORD, "新增", p.name(), target,
                        null, AuditService.formatNumber(c.value()));
                changedDates.add(c.date());
                changed++;
            } else if (Double.compare(old.value(), c.value()) != 0) {
                recordMapper.updateValue(old.id(), c.value(), op.username());
                auditService.log(op, AuditService.MODULE_RECORD, "修改", p.name(), target,
                        AuditService.formatNumber(old.value()), AuditService.formatNumber(c.value()));
                changedDates.add(c.date());
                changed++;
            }
        }

        for (LocalDate d : changedDates) {
            snapshotMapper.insert(processId, p.name(), d, buildSnapshot(p, d, op.username()), op.username());
        }
        return changed;
    }

    @Transactional
    public void saveTargets(LoginUser op, Integer processId, List<Requests.TargetItem> items) {
        ProcessStep p = processService.requireProcess(processId);
        checkCanEdit(op, p);
        Map<Metric, MetricTarget> existing = targetMapper.selectByProcess(processId);
        for (Requests.TargetItem item : items == null ? List.<Requests.TargetItem>of() : items) {
            Metric metric = Metric.valueOf(item.metric());
            String compareOp = "GE".equals(item.compareOp()) ? "GE" : "LE";
            MetricTarget next = new MetricTarget(processId, metric, trim(item.baseline()), trim(item.teamTarget()),
                    trim(item.unit()), compareOp, item.targetValue(), op.username(), null);
            MetricTarget old = existing.get(metric);
            if (old == null) {
                targetMapper.insert(next);
                auditService.log(op, AuditService.MODULE_TARGET, "新增", p.name(), metric.getLabel() + " 目标", null, describe(next));
            } else if (!describe(old).equals(describe(next))) {
                targetMapper.update(next);
                auditService.log(op, AuditService.MODULE_TARGET, "修改", p.name(), metric.getLabel() + " 目标", describe(old), describe(next));
            }
        }
    }

    @Transactional
    public void saveBoard(LoginUser op, Integer processId, Requests.SaveBoard req) {
        ProcessStep p = processService.requireProcess(processId);
        checkCanEdit(op, p);
        ProcessBoard old = boardMapper.selectByProcess(processId);
        List<ProcessBoard.Member> members = new ArrayList<>();
        if (req.members() != null) {
            if (req.members().size() > 4) {
                throw new BizException("四功能小组最多 4 个人");
            }
            for (Requests.BoardMember m : req.members()) {
                members.add(new ProcessBoard.Member(trim(m.photo()), trim(m.name()), trim(m.role())));
            }
        }
        ProcessBoard next = new ProcessBoard(processId, members, trim(req.signers()), req.signDate(), op.username(), null);
        boardMapper.upsert(next);
        auditService.log(op, AuditService.MODULE_BOARD, old == null ? "新增" : "修改", p.name(), "班组看板",
                old == null ? null : describe(old), describe(next));
    }

    public List<Map<String, Object>> snapshots(Integer processId, LocalDate date) {
        processService.requireProcess(processId);
        return snapshotMapper.selectByProcessAndDate(processId, date);
    }

    // ---------------- 内部方法 ----------------

    private void checkCanEdit(LoginUser op, ProcessStep p) {
        if (!p.active()) {
            throw new BizException("“" + p.name() + "”已停用，数据只能查看不能修改；需要修改请先在“工序配置”里重新启用");
        }
        if (!op.canEdit(p.id())) {
            throw new BizException("你不是“" + p.name() + "”的负责人，不能编辑该工序的数据");
        }
    }

    /** 某工序某天的完整战报：六项数值、单位、目标、是否达标、填报人 */
    private String buildSnapshot(ProcessStep p, LocalDate date, String savedBy) {
        Map<Metric, MetricTarget> targets = targetMapper.selectByProcess(p.id());
        Map<Metric, DailyRecord> records = new EnumMap<>(Metric.class);
        recordMapper.selectByProcess(p.id(), date, date).forEach(r -> records.put(r.metric(), r));

        List<Map<String, Object>> items = new ArrayList<>();
        int pass = 0, fail = 0;
        for (Metric m : Metric.values()) {
            MetricTarget t = targets.get(m);
            DailyRecord r = records.get(m);
            Double value = r == null ? null : r.value();
            MetricStatus status = MetricStatus.judge(value, t == null ? null : t.compareOp(), t == null ? null : t.targetValue());
            if (status == MetricStatus.PASS) pass++;
            if (status == MetricStatus.FAIL) fail++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metric", m.name());
            item.put("label", m.getLabel());
            item.put("value", value);
            item.put("unit", t == null ? null : t.unit());
            item.put("baseline", t == null ? null : t.baseline());
            item.put("teamTarget", t == null ? null : t.teamTarget());
            item.put("compareOp", t == null ? null : t.compareOp());
            item.put("targetValue", t == null ? null : t.targetValue());
            item.put("status", status);
            item.put("createdBy", r == null ? null : r.createdBy());
            item.put("updatedBy", r == null ? null : r.updatedBy());
            items.add(item);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("process", p.name());
        snapshot.put("date", date.toString());
        snapshot.put("pass", pass);
        snapshot.put("fail", fail);
        snapshot.put("savedBy", savedBy);
        snapshot.put("items", items);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> describe(MetricTarget t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("公司底线", nvl(t.baseline()));
        m.put("班组自定", nvl(t.teamTarget()));
        m.put("单位", nvl(t.unit()));
        m.put("达标判定", ("GE".equals(t.compareOp()) ? "≥ " : "≤ ") + AuditService.formatNumber(t.targetValue()));
        return m;
    }

    private static Map<String, Object> describe(ProcessBoard b) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<ProcessBoard.Member> members = b.members() == null ? List.of() : b.members();
        for (int i = 0; i < 4; i++) {
            ProcessBoard.Member p = i < members.size() ? members.get(i) : null;
            String who = p == null ? "" : (nvl(p.name()) + (p.role() == null ? "" : " · " + p.role())).trim();
            String photo = p == null || p.photo() == null ? "" : "（照片 " + p.photo() + "）";
            m.put("成员" + (i + 1), who + photo);
        }
        m.put("全员签名", nvl(b.signers()));
        m.put("签名日期", b.signDate() == null ? "" : b.signDate().toString());
        return m;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
