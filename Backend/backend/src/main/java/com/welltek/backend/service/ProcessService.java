package com.welltek.backend.service;

import com.welltek.backend.config.BizException;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.entity.*;
import com.welltek.backend.mapper.ItemMapper;
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

// 【工序】工序列表（选项卡页）、工序配置（新增 / 改名 / 停用 / 删除 / 恢复）、工序负责人分配
@Service
public class ProcessService {

    @Autowired
    private ProcessMapper processMapper;

    @Autowired
    private TargetMapper targetMapper;

    @Autowired
    private ItemMapper itemMapper;

    @Autowired
    private RecordMapper recordMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AuditService auditService;

    /**
     * 选项卡页：每道工序一张卡片，带负责人、当天填了几项、几项未达标、当前用户能不能编辑。
     * 拆了子项的指标：所有子项都达标才算达标，有子项没填算没填完。
     */
    public List<Map<String, Object>> listCards(LoginUser op, LocalDate date) {
        Map<Integer, List<Map<String, Object>>> owners = processMapper.selectOwners();
        Map<Integer, List<MetricItem>> items = itemMapper.selectAll();
        Map<Integer, List<DailyRecord>> records = recordMapper.selectAll(date, date).stream()
                .collect(Collectors.groupingBy(DailyRecord::processId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessStep p : processMapper.selectAll(false)) {
            Map<Metric, MetricStatus> statuses = MetricJudge.day(items.getOrDefault(p.id(), List.of()),
                    MetricJudge.values(records.getOrDefault(p.id(), List.of())));
            int filled = 0, fail = 0, pass = 0;
            List<String> failMetrics = new ArrayList<>();
            List<String> passMetrics = new ArrayList<>();
            List<String> emptyMetrics = new ArrayList<>();
            for (Map.Entry<Metric, MetricStatus> e : statuses.entrySet()) {
                String label = e.getKey().getLabel();
                switch (e.getValue()) {
                    case PASS -> { filled++; pass++; passMetrics.add(label); }
                    case FAIL -> { filled++; fail++; failMetrics.add(label); }
                    case EMPTY -> emptyMetrics.add(label);
                    default -> filled++;
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

    /** 工序配置 / 工序分配页用：没删除的全部工序（含停用的）+ 负责人 */
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

    /** 工序配置页“已删除的工序” */
    public List<Map<String, Object>> listDeleted() {
        return processMapper.selectDeleted();
    }

    @Transactional
    public void create(LoginUser op, Requests.SaveProcess req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new BizException("请填写工序名称");
        }
        checkNameFree(name, null);
        int sort = req.sortOrder() != null ? req.sortOrder() : processMapper.maxSortOrder() + 10;
        Integer id = processMapper.insert(name, sort);
        ensureTargets(id);
        auditService.log(op, AuditService.MODULE_PROCESS, "新增", name, "工序 " + name, null,
                Map.of("名称", name, "排序", sort));
    }

    @Transactional
    public void update(LoginUser op, Integer id, Requests.SaveProcess req) {
        ProcessStep old = requireProcess(id);
        if (old.deleted()) {
            throw new BizException("“" + old.name() + "”已删除，请先恢复再修改");
        }
        String name = req.name() == null || req.name().isBlank() ? old.name() : req.name().trim();
        checkNameFree(name, id);
        int sort = req.sortOrder() == null ? old.sortOrder() : req.sortOrder();
        boolean active = req.active() == null ? old.active() : req.active();
        processMapper.update(id, name, sort, active);
        auditService.log(op, AuditService.MODULE_PROCESS, "修改", name, "工序 " + old.name(),
                describe(old.name(), old.sortOrder(), old.active()), describe(name, sort, active));
    }

    /**
     * 删除工序：只做标记，各个页面不再显示；填过的数据、目标、看板、修改记录、留底全部留在库里，可以恢复。
     * 同时解除负责人（记在操作日志里），免得负责人名下挂着看不到的工序。
     */
    @Transactional
    public void delete(LoginUser op, Integer id) {
        ProcessStep p = requireProcess(id);
        if (p.deleted()) {
            throw new BizException("“" + p.name() + "”已经删除过了");
        }
        String owners = processMapper.selectOwners().getOrDefault(id, List.of()).stream()
                .map(o -> (String) o.get("username")).collect(Collectors.joining("、"));
        processMapper.markDeleted(id, op.username());
        userMapper.replaceOwners(id, List.of());
        Map<String, Object> before = describe(p.name(), p.sortOrder(), p.active());
        before.put("负责人", owners.isEmpty() ? "（无）" : owners);
        auditService.log(op, AuditService.MODULE_PROCESS, "删除", p.name(), "工序 " + p.name(), before,
                Map.of("状态", "已删除（数据保留，可在工序配置里恢复）"));
    }

    /** 恢复已删除的工序：回到工序配置列表并重新启用；负责人需要重新分配 */
    @Transactional
    public void restore(LoginUser op, Integer id) {
        ProcessStep p = requireProcess(id);
        if (!p.deleted()) {
            throw new BizException("“" + p.name() + "”没有被删除");
        }
        processMapper.restore(id);
        ensureTargets(id);
        auditService.log(op, AuditService.MODULE_PROCESS, "恢复", p.name(), "工序 " + p.name(),
                Map.of("状态", "已删除"), Map.of("状态", "启用"));
    }

    /** 工序分配页：按工序整体设置负责人 */
    @Transactional
    public void setOwners(LoginUser op, Integer id, List<Integer> userIds) {
        ProcessStep p = requireProcess(id);
        if (p.deleted()) {
            throw new BizException("“" + p.name() + "”已删除，不能分配负责人");
        }
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

    /** 补齐某道工序缺少的六项默认目标和默认子项（新增工序、系统初始化时调用） */
    public void ensureTargets(Integer processId) {
        Map<Metric, MetricTarget> existing = targetMapper.selectByProcess(processId);
        Map<Metric, List<MetricItem>> items = MetricJudge.group(itemMapper.selectByProcess(processId));
        for (Default d : DEFAULTS) {
            if (!existing.containsKey(d.metric())) {
                targetMapper.insert(new MetricTarget(processId, d.metric(), d.baseline(), d.teamTarget(), "系统", null));
            }
            if (items.get(d.metric()).isEmpty()) {
                itemMapper.insert(new MetricItem(null, processId, d.metric(), null, d.unit(), d.compareOp(), d.targetValue(),
                        0, true, "系统", null));
            }
        }
    }

    private void checkNameFree(String name, Integer selfId) {
        ProcessStep same = processMapper.selectByName(name);
        if (same == null || same.id().equals(selfId)) {
            return;
        }
        throw new BizException(same.deleted()
                ? "“" + name + "”是已删除的工序，可以在下方“已删除的工序”里恢复，或换一个名称"
                : "工序“" + name + "”已存在");
    }

    private record Default(Metric metric, String baseline, String teamTarget, String unit, String compareOp, Double targetValue) {
    }

    // 默认值（2026-10-24 按车间提供的保证书调整），只用在新建的工序上，已有工序的目标不会被改；
    // 各工序负责人可以在详情页里改成自己的（也可以拆子项）
    private static final List<Default> DEFAULTS = List.of(
            new Default(Metric.SAFETY, "0 事故", "0 事故", "起", "LE", 0d),
            new Default(Metric.QUALITY, "客诉 -50%", "0 流出", "片", "LE", 0d),
            new Default(Metric.DELIVERY, "3000 / 日", "≥ 3000 ㎡", "㎡", "GE", 3000d),
            new Default(Metric.SCRAP, "≤ 3%", "≤ 3%", "%", "LE", 3d),
            new Default(Metric.COST, "-15%", "≤ 0 kg", "KG", "LE", 0d),
            new Default(Metric.EQUIPMENT, "-30%", "≤ 1.0 h", "h", "LE", 1.0));

    private static Map<String, Object> describe(String name, int sort, boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("名称", name);
        m.put("排序", sort);
        m.put("状态", active ? "启用" : "停用");
        return m;
    }
}
