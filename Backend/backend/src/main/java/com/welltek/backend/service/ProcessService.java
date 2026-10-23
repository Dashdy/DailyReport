package com.welltek.backend.service;

import com.welltek.backend.config.BizException;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.entity.*;
import com.welltek.backend.mapper.ProcessMapper;
import com.welltek.backend.mapper.RecordMapper;
import com.welltek.backend.mapper.TargetMapper;
import com.welltek.backend.mapper.UserMapper;
import com.welltek.backend.security.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

// 【工序】工序列表（选项卡页）、工序配置、工序负责人分配
@Service
public class ProcessService {

    @Autowired
    private ProcessMapper processMapper;

    @Autowired
    private TargetMapper targetMapper;

    @Autowired
    private RecordMapper recordMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AuditService auditService;

    /**
     * 选项卡页：每道工序一张卡片，带负责人、当天填了几项、几项未达标、当前用户能不能编辑。
     */
    public List<Map<String, Object>> listCards(LoginUser op, LocalDate date) {
        Map<Integer, List<Map<String, Object>>> owners = processMapper.selectOwners();
        Map<Integer, Map<Metric, MetricTarget>> targets = targetMapper.selectAll();
        Map<Integer, Map<Metric, Double>> values = new HashMap<>();
        for (DailyRecord r : recordMapper.selectAll(date, date)) {
            values.computeIfAbsent(r.processId(), k -> new EnumMap<>(Metric.class)).put(r.metric(), r.value());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessStep p : processMapper.selectAll(false)) {
            Map<Metric, Double> v = values.getOrDefault(p.id(), Map.of());
            Map<Metric, MetricTarget> t = targets.getOrDefault(p.id(), Map.of());
            int filled = 0, fail = 0, pass = 0;
            List<String> failMetrics = new ArrayList<>();
            List<String> passMetrics = new ArrayList<>();
            List<String> emptyMetrics = new ArrayList<>();
            for (Metric m : Metric.values()) {
                MetricTarget mt = t.get(m);
                MetricStatus s = MetricStatus.judge(v.get(m), mt == null ? null : mt.compareOp(), mt == null ? null : mt.targetValue());
                if (s != MetricStatus.EMPTY) filled++;
                if (s == MetricStatus.EMPTY) emptyMetrics.add(m.getLabel());
                if (s == MetricStatus.PASS) {
                    pass++;
                    passMetrics.add(m.getLabel());
                }
                if (s == MetricStatus.FAIL) {
                    fail++;
                    failMetrics.add(m.getLabel());
                }
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", p.id());
            card.put("name", p.name());
            card.put("owners", owners.getOrDefault(p.id(), List.of()));
            card.put("canEdit", op.canEdit(p.id()));
            card.put("filled", filled);
            card.put("pass", pass);
            card.put("fail", fail);
            card.put("failMetrics", failMetrics);
            card.put("passMetrics", passMetrics);
            card.put("emptyMetrics", emptyMetrics);
            result.add(card);
        }
        return result;
    }

    /** 工序配置 / 工序分配页用：全部工序（含停用的）+ 负责人 */
    public List<Map<String, Object>> listAll() {
        Map<Integer, List<Map<String, Object>>> owners = processMapper.selectOwners();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessStep p : processMapper.selectAll(true)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("sortOrder", p.sortOrder());
            m.put("active", p.active());
            m.put("owners", owners.getOrDefault(p.id(), List.of()));
            result.add(m);
        }
        return result;
    }

    @Transactional
    public void create(LoginUser op, Requests.SaveProcess req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new BizException("请填写工序名称");
        }
        if (processMapper.selectByName(name) != null) {
            throw new BizException("工序“" + name + "”已存在");
        }
        int sort = req.sortOrder() != null ? req.sortOrder() : processMapper.maxSortOrder() + 10;
        Integer id = processMapper.insert(name, sort);
        ensureTargets(id);
        auditService.log(op, AuditService.MODULE_PROCESS, "新增", name, "工序 " + name, null,
                Map.of("名称", name, "排序", sort));
    }

    @Transactional
    public void update(LoginUser op, Integer id, Requests.SaveProcess req) {
        ProcessStep old = requireProcess(id);
        String name = req.name() == null || req.name().isBlank() ? old.name() : req.name().trim();
        ProcessStep sameName = processMapper.selectByName(name);
        if (sameName != null && !sameName.id().equals(id)) {
            throw new BizException("工序“" + name + "”已存在");
        }
        int sort = req.sortOrder() == null ? old.sortOrder() : req.sortOrder();
        boolean active = req.active() == null ? old.active() : req.active();
        processMapper.update(id, name, sort, active);
        auditService.log(op, AuditService.MODULE_PROCESS, "修改", name, "工序 " + old.name(),
                describe(old.name(), old.sortOrder(), old.active()), describe(name, sort, active));
    }

    /** 工序分配页：按工序整体设置负责人 */
    @Transactional
    public void setOwners(LoginUser op, Integer id, List<Integer> userIds) {
        ProcessStep p = requireProcess(id);
        List<Integer> newIds = userIds == null ? List.of() : userIds;
        Map<Integer, String> userNames = userMapper.selectAll().stream()
                .collect(Collectors.toMap(User::id, User::username));
        String before = processMapper.selectOwners().getOrDefault(id, List.of()).stream()
                .map(o -> (String) o.get("username")).collect(Collectors.joining("、"));
        userMapper.replaceOwners(id, newIds);
        String after = newIds.stream().map(userNames::get).filter(Objects::nonNull).collect(Collectors.joining("、"));
        auditService.log(op, AuditService.MODULE_ASSIGN, "修改", p.name(), "工序 " + p.name() + " 的负责人",
                before.isEmpty() ? "（无）" : before, after.isEmpty() ? "（无）" : after);
    }

    public ProcessStep requireProcess(Integer id) {
        ProcessStep p = processMapper.selectById(id);
        if (p == null) {
            throw new BizException("工序不存在");
        }
        return p;
    }

    /** 补齐某道工序缺少的六项默认目标（新增工序、系统初始化时调用） */
    public void ensureTargets(Integer processId) {
        Map<Metric, MetricTarget> existing = targetMapper.selectByProcess(processId);
        for (MetricTarget t : defaultTargets(processId)) {
            if (!existing.containsKey(t.metric())) {
                targetMapper.insert(t);
            }
        }
    }

    // 默认值来自“XX工序 六项自主保证书”模板，各工序负责人可以在详情页里改成自己的
    private static List<MetricTarget> defaultTargets(Integer pid) {
        return List.of(
                new MetricTarget(pid, Metric.SAFETY, "0 事故", "0 事故", "起", "LE", 0d, "系统", null),
                new MetricTarget(pid, Metric.QUALITY, "客诉 -50%", "本班 0 流出", "片", "LE", 0d, "系统", null),
                new MetricTarget(pid, Metric.DELIVERY, "3000 / 日", "本班 320 片", "片", "GE", 320d, "系统", null),
                new MetricTarget(pid, Metric.SCRAP, "≤ 3%", "≤ 2.6%", "%", "LE", 2.6, "系统", null),
                new MetricTarget(pid, Metric.COST, "-15%", "0.95 倍标", "倍标", "LE", 0.95, "系统", null),
                new MetricTarget(pid, Metric.EQUIPMENT, "-30%", "≤ 1.0h / 班", "h", "LE", 1.0, "系统", null));
    }

    private static Map<String, Object> describe(String name, int sort, boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("名称", name);
        m.put("排序", sort);
        m.put("状态", active ? "启用" : "停用");
        return m;
    }
}
