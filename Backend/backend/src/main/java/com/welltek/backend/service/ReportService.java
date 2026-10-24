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
// 每项指标可以拆成多个子项（如 成本 → 锡球、铜球），走势表每个子项一行，单独填数、单独判定红绿；
// 一项指标的所有子项都达标，这项才算达标。
// 填报规则：未来日期谁都不能填；工序负责人只能在当天锁定时间（系统设置里定义）之前填当天的数据，
// 以前的和过了锁定时间的数据只有系统管理员 / 管理员能更正。
// 留存规则：
// 1. 每一格的新增 / 修改 / 清空都写一条操作日志（操作人、原值、新值）；
// 2. 每格记录首次填报人和最后保存人；
// 3. 每次保存后，被改动的那几天各存一份完整战报快照（DAILY_SNAPSHOT），版本号递增，只增不改。
@Service
public class ReportService {

    /** 一项指标最多拆几个子项 */
    private static final int MAX_ITEMS = 6;

    @Autowired
    private ProcessService processService;

    @Autowired
    private TargetMapper targetMapper;

    @Autowired
    private ItemMapper itemMapper;

    @Autowired
    private RecordMapper recordMapper;

    @Autowired
    private BoardMapper boardMapper;

    @Autowired
    private SnapshotMapper snapshotMapper;

    @Autowired
    private NoteMapper noteMapper;

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
        List<MetricItem> items = itemMapper.selectByProcess(processId);
        Map<Integer, MetricItem> itemById = new HashMap<>();
        items.forEach(i -> itemById.put(i.id(), i));

        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> targetList = new ArrayList<>();
        MetricJudge.group(items).forEach((m, list) -> {
            metrics.add(Map.of("key", m.name(), "label", m.getLabel()));
            MetricTarget t = targets.get(m);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("metric", m.name());
            tm.put("label", m.getLabel());
            tm.put("baseline", t == null ? null : t.baseline());
            tm.put("teamTarget", MetricJudge.targetText(list)); // 由子项自动生成，跟判红绿的规则永远一致
            tm.put("updatedBy", t == null ? null : t.updatedBy());
            tm.put("updatedAt", t == null ? null : t.updatedAt());
            tm.put("items", list.stream().map(ReportService::itemView).toList());
            targetList.add(tm);
        });

        List<Map<String, Object>> records = new ArrayList<>();
        for (DailyRecord r : recordMapper.selectByProcess(processId, month.atDay(1), month.atEndOfMonth())) {
            MetricItem item = itemById.get(r.itemId());
            if (item == null) {
                continue; // 已删除的子项：数据留在库里，页面上不再显示
            }
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("date", r.reportDate());
            rm.put("metric", r.metric().name());
            rm.put("itemId", r.itemId());
            rm.put("value", r.value());
            rm.put("status", item.judge(r.value()));
            rm.put("createdBy", r.createdBy());
            rm.put("createdAt", r.createdAt());
            rm.put("updatedBy", r.updatedBy());
            rm.put("updatedAt", r.updatedAt());
            records.add(rm);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("process", Map.of("id", p.id(), "name", p.name(), "active", p.active(), "deleted", p.deleted()));
        result.put("owners", processMapper.selectOwners().getOrDefault(processId, List.of()));
        // 停用 / 删除的工序只读：历史数据照样能看，谁都不能再改
        result.put("canEdit", p.active() && !p.deleted() && op.canEdit(processId));
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
        result.put("notes", noteMapper.selectByProcess(processId, month.atDay(1), month.atEndOfMonth()));
        return result;
    }

    /**
     * 保存某天的 今日行动措施 / 今日亮点 / 明日行动计划。
     * 跟走势表同一套规则：未来日期不能填；负责人受“数据锁定”限制，管理员不受限制。
     */
    @Transactional
    public void saveNote(LoginUser op, Integer processId, Requests.SaveNote req) {
        ProcessStep p = processService.requireProcess(processId);
        checkCanEdit(op, p);
        LocalDate date = req.date();
        if (date == null) {
            throw new BizException("请指定日期");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new BizException("不能填写未来日期（" + date + "）的内容");
        }
        if (!op.isAdmin() && !settingService.ownerCanEdit(date)) {
            throw new BizException(date.equals(LocalDate.now())
                    ? "今天的数据已按“" + settingService.lockRuleText() + "”锁定，需要修改请联系管理员"
                    : date + " 的数据已锁定，需要更正请联系管理员");
        }
        DailyNote next = new DailyNote(processId, date, note(req.actions(), "今日行动措施"), note(req.highlights(), "今日亮点"),
                note(req.plans(), "明日行动计划"), op.username(), null);
        DailyNote old = noteMapper.selectOne(processId, date);
        Map<String, Object> before = old == null ? null : describe(old);
        if (Objects.equals(before, describe(next)) || (old == null && next.isEmpty())) {
            return;
        }
        noteMapper.upsert(next);
        auditService.log(op, AuditService.MODULE_NOTE, old == null ? "新增" : "修改", p.name(), date + " 行动与亮点", before, describe(next));
    }

    private static String note(String s, String label) {
        String v = s == null ? null : s.strip();
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.length() > 1000) {
            throw new BizException("“" + label + "”最多 1000 个字");
        }
        return v;
    }

    /** 保存走势表：只处理有变化的格子，返回实际改动了几格 */
    @Transactional
    public int saveRecords(LoginUser op, Integer processId, List<Requests.Cell> cells) {
        ProcessStep p = processService.requireProcess(processId);
        checkCanEdit(op, p);
        if (cells == null || cells.isEmpty()) {
            return 0;
        }
        Map<Integer, MetricItem> itemById = new HashMap<>();
        itemMapper.selectByProcess(processId).forEach(i -> itemById.put(i.id(), i));

        LocalDate today = LocalDate.now();
        String lockRule = settingService.lockRuleText();
        Set<LocalDate> changedDates = new TreeSet<>();
        int changed = 0;
        for (Requests.Cell c : cells) {
            if (c.date() == null || c.itemId() == null) {
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
            MetricItem item = itemById.get(c.itemId());
            if (item == null) {
                throw new BizException("有子项已被删除或修改过，请刷新页面后重新填写");
            }
            String target = c.date() + " " + item.label();
            DailyRecord old = recordMapper.selectOne(item.id(), c.date());

            if (c.value() == null) {
                if (old != null) {
                    recordMapper.delete(old.id());
                    auditService.log(op, AuditService.MODULE_RECORD, "清空", p.name(), target,
                            AuditService.formatNumber(old.value()), null);
                    changedDates.add(c.date());
                    changed++;
                }
            } else if (old == null) {
                recordMapper.insert(processId, c.date(), item.metric(), item.id(), c.value(), op.username());
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

    /**
     * 保存六项目标：每项的 公司底线 / 班组自定 文字 + 子项（名称、单位、判定规则）。
     * 子项带 id 的是修改，不带 id 的是新加；原来有、这次没传的子项删除（不再显示，已填的数据留在库里）。
     */
    @Transactional
    public void saveTargets(LoginUser op, Integer processId, List<Requests.TargetItem> list) {
        ProcessStep p = processService.requireProcess(processId);
        checkCanEdit(op, p);
        Map<Metric, MetricTarget> existing = targetMapper.selectByProcess(processId);
        Map<Metric, List<MetricItem>> existingItems = MetricJudge.group(itemMapper.selectByProcess(processId));

        for (Requests.TargetItem req : list == null ? List.<Requests.TargetItem>of() : list) {
            Metric metric;
            try {
                metric = Metric.valueOf(req.metric());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BizException("未知的指标：" + req.metric());
            }

            // ---- 公司底线（班组自定 2026-10-24 起由子项自动生成；库里原来写的文字原样保留，不改） ----
            MetricTarget old = existing.get(metric);
            String teamText = req.teamTarget() != null ? trim(req.teamTarget()) : old == null ? null : old.teamTarget();
            MetricTarget next = new MetricTarget(processId, metric, trim(req.baseline()), teamText, op.username(), null);
            if (old == null) {
                targetMapper.insert(next);
                auditService.log(op, AuditService.MODULE_TARGET, "新增", p.name(), metric.getLabel() + " 目标", null, describe(next));
            } else if (!describe(old).equals(describe(next))) {
                targetMapper.update(next);
                auditService.log(op, AuditService.MODULE_TARGET, "修改", p.name(), metric.getLabel() + " 目标", describe(old), describe(next));
            }

            // ---- 子项 ----
            List<Requests.SubItem> subs = req.items() == null ? List.of() : req.items();
            validateItems(metric, subs);
            Map<Integer, MetricItem> olds = new LinkedHashMap<>();
            existingItems.get(metric).forEach(i -> olds.put(i.id(), i));
            Set<Integer> kept = new HashSet<>();
            for (int idx = 0; idx < subs.size(); idx++) {
                Requests.SubItem s = subs.get(idx);
                String compareOp = "GE".equals(s.compareOp()) ? "GE" : "LE";
                if (s.id() == null) {
                    MetricItem item = new MetricItem(null, processId, metric, trim(s.name()), trim(s.unit()), compareOp,
                            s.targetValue(), idx, true, op.username(), null);
                    itemMapper.insert(item);
                    auditService.log(op, AuditService.MODULE_TARGET, "新增", p.name(), item.label() + " 子项", null, describe(item));
                    continue;
                }
                MetricItem before = olds.get(s.id());
                if (before == null) {
                    throw new BizException("“" + metric.getLabel() + "”的子项已被别人修改过，请关掉弹窗、刷新页面后重新编辑");
                }
                kept.add(s.id());
                MetricItem after = new MetricItem(before.id(), processId, metric, trim(s.name()), trim(s.unit()), compareOp,
                        s.targetValue(), idx, true, op.username(), null);
                boolean contentChanged = !describe(before).equals(describe(after));
                if (contentChanged || before.sortOrder() != idx) {
                    itemMapper.update(after);
                }
                if (contentChanged) {
                    auditService.log(op, AuditService.MODULE_TARGET, "修改", p.name(), after.label() + " 子项", describe(before), describe(after));
                }
            }
            for (MetricItem gone : olds.values()) {
                if (!kept.contains(gone.id())) {
                    itemMapper.deactivate(gone.id(), op.username());
                    auditService.log(op, AuditService.MODULE_TARGET, "删除", p.name(), gone.label() + " 子项", describe(gone), null);
                }
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
        // 全员签名、日期页面上已经去掉了，库里原来填的保留不动
        ProcessBoard next = new ProcessBoard(processId, members, old == null ? null : old.signers(),
                old == null ? null : old.signDate(), op.username(), null);
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
        if (p.deleted()) {
            throw new BizException("“" + p.name() + "”已删除，数据只能查看不能修改；需要修改请先在“工序配置”里恢复");
        }
        if (!p.active()) {
            throw new BizException("“" + p.name() + "”已停用，数据只能查看不能修改；需要修改请先在“工序配置”里重新启用");
        }
        if (!op.canEdit(p.id())) {
            throw new BizException("你不是“" + p.name() + "”的负责人，不能编辑该工序的数据");
        }
    }

    /** 子项校验：至少一个；拆成多个时每个都要有名字，且不能重名 */
    private static void validateItems(Metric metric, List<Requests.SubItem> subs) {
        String label = metric.getLabel();
        if (subs.isEmpty()) {
            throw new BizException("“" + label + "”至少要保留一个子项");
        }
        if (subs.size() > MAX_ITEMS) {
            throw new BizException("“" + label + "”最多拆 " + MAX_ITEMS + " 个子项");
        }
        Set<String> names = new HashSet<>();
        for (Requests.SubItem s : subs) {
            String name = trim(s.name());
            if (subs.size() > 1 && name == null) {
                throw new BizException("“" + label + "”拆成了 " + subs.size() + " 个子项，每个子项都要填名称（如 锡球、铜球）");
            }
            if (name != null && name.length() > 30) {
                throw new BizException("子项名称最多 30 个字：" + name);
            }
            if (name != null && !names.add(name)) {
                throw new BizException("“" + label + "”的子项名称重复：" + name);
            }
        }
    }

    private static Map<String, Object> itemView(MetricItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("name", i.name());
        m.put("label", i.label());
        m.put("unit", i.unit());
        m.put("compareOp", i.compareOp());
        m.put("targetValue", i.targetValue());
        return m;
    }

    /**
     * 某工序某天的完整战报：每个子项一行（数值、单位、目标、是否达标、填报人），
     * pass / fail 按指标算（所有子项都达标这项才算达标）。
     */
    private String buildSnapshot(ProcessStep p, LocalDate date, String savedBy) {
        Map<Metric, MetricTarget> targets = targetMapper.selectByProcess(p.id());
        List<MetricItem> items = itemMapper.selectByProcess(p.id());
        Map<Integer, DailyRecord> records = new HashMap<>();
        recordMapper.selectByProcess(p.id(), date, date).forEach(r -> records.put(r.itemId(), r));
        Map<Integer, Double> values = MetricJudge.values(records.values());

        List<Map<String, Object>> rows = new ArrayList<>();
        int pass = 0, fail = 0;
        for (Map.Entry<Metric, List<MetricItem>> e : MetricJudge.group(items).entrySet()) {
            Metric m = e.getKey();
            MetricTarget t = targets.get(m);
            MetricStatus metricStatus = MetricJudge.metric(e.getValue(), values);
            if (metricStatus == MetricStatus.PASS) pass++;
            if (metricStatus == MetricStatus.FAIL) fail++;
            for (MetricItem item : e.getValue()) {
                DailyRecord r = records.get(item.id());
                Double value = r == null ? null : r.value();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metric", m.name());
                row.put("label", item.label());
                row.put("item", item.name());
                row.put("value", value);
                row.put("unit", item.unit());
                row.put("baseline", t == null ? null : t.baseline());
                row.put("teamTarget", MetricJudge.ruleText(item));
                row.put("compareOp", item.compareOp());
                row.put("targetValue", item.targetValue());
                row.put("status", item.judge(value));
                row.put("metricStatus", metricStatus);
                row.put("createdBy", r == null ? null : r.createdBy());
                row.put("updatedBy", r == null ? null : r.updatedBy());
                rows.add(row);
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("process", p.name());
        snapshot.put("date", date.toString());
        snapshot.put("pass", pass);
        snapshot.put("fail", fail);
        snapshot.put("savedBy", savedBy);
        snapshot.put("items", rows);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> describe(MetricTarget t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("公司底线", nvl(t.baseline()));
        return m;
    }

    private static Map<String, Object> describe(MetricItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("名称", nvl(i.name()));
        m.put("单位", nvl(i.unit()));
        m.put("达标判定", ("GE".equals(i.compareOp()) ? "≥ " : "≤ ") + AuditService.formatNumber(i.targetValue()));
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
        return m;
    }

    private static Map<String, Object> describe(DailyNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("今日行动措施", nvl(n.actions()));
        m.put("今日亮点", nvl(n.highlights()));
        m.put("明日行动计划", nvl(n.plans()));
        return m;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
