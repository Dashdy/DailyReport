package com.welltek.backend.service;

import com.welltek.backend.entity.*;
import com.welltek.backend.mapper.ItemMapper;
import com.welltek.backend.mapper.ProcessMapper;
import com.welltek.backend.mapper.RecordMapper;
import com.welltek.backend.mapper.TargetMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

// 【首页：全厂达标汇总】
// - metrics：六项指标各有多少工序达标 / 未达标 / 未填报；
// - overall：全厂合计；
// - rows：工序 × 六项的达标矩阵（拆了子项的格子带上每个子项的值和红绿）；
// - trend：当月 1 号到所选日期，每天每项指标的达标率（折线图）。
// 一项指标的所有子项都达标，这项才算达标；达标率 = 达标工序数 ÷ 已填报且设了目标的工序数，未填报的不算进分母。
@Service
public class DashboardService {

    @Autowired
    private ProcessMapper processMapper;

    @Autowired
    private TargetMapper targetMapper;

    @Autowired
    private ItemMapper itemMapper;

    @Autowired
    private RecordMapper recordMapper;

    public Map<String, Object> summary(LocalDate date) {
        List<ProcessStep> processes = processMapper.selectAll(false);
        Map<Integer, Map<Metric, MetricTarget>> targets = targetMapper.selectAll();
        Map<Integer, List<MetricItem>> items = itemMapper.selectAll();
        Map<Integer, List<Map<String, Object>>> owners = processMapper.selectOwners();

        LocalDate monthStart = date.withDayOfMonth(1);
        // processId → date → itemId → value
        Map<Integer, Map<LocalDate, Map<Integer, Double>>> values = new HashMap<>();
        for (DailyRecord r : recordMapper.selectAll(monthStart, date)) {
            values.computeIfAbsent(r.processId(), k -> new HashMap<>())
                    .computeIfAbsent(r.reportDate(), k -> new HashMap<>())
                    .put(r.itemId(), r.value());
        }

        // ---- 当天：矩阵 + 各指标统计 ----
        Map<Metric, int[]> counts = new EnumMap<>(Metric.class); // [pass, fail, empty]
        for (Metric m : Metric.values()) counts.put(m, new int[3]);

        List<Map<String, Object>> rows = new ArrayList<>();
        int reported = 0, allPass = 0;
        for (ProcessStep p : processes) {
            Map<Integer, Double> v = values.getOrDefault(p.id(), Map.of()).getOrDefault(date, Map.of());
            Map<Metric, MetricTarget> t = targets.getOrDefault(p.id(), Map.of());
            Map<String, Object> cells = new LinkedHashMap<>();
            int pass = 0, fail = 0, empty = 0;
            boolean any = false; // 有效子项里至少填了一个数，就算“已填报”
            for (Map.Entry<Metric, List<MetricItem>> e : MetricJudge.group(items.getOrDefault(p.id(), List.of())).entrySet()) {
                Metric m = e.getKey();
                List<MetricItem> list = e.getValue();
                MetricStatus s = MetricJudge.metric(list, v);
                MetricTarget mt = t.get(m);
                List<Map<String, Object>> subs = new ArrayList<>();
                for (MetricItem item : list) {
                    Map<String, Object> sub = new LinkedHashMap<>();
                    sub.put("name", item.name());
                    sub.put("value", v.get(item.id()));
                    sub.put("unit", item.unit());
                    sub.put("status", item.judge(v.get(item.id())));
                    any |= v.get(item.id()) != null;
                    subs.add(sub);
                }
                Map<String, Object> cell = new LinkedHashMap<>();
                // 只有一个子项时直接给值；拆了子项的看 items
                cell.put("value", list.size() == 1 ? v.get(list.get(0).id()) : null);
                cell.put("unit", list.size() == 1 ? list.get(0).unit() : null);
                cell.put("items", subs);
                cell.put("teamTarget", MetricJudge.targetText(list));
                cell.put("status", s);
                cells.put(m.name(), cell);
                switch (s) {
                    case PASS -> { pass++; counts.get(m)[0]++; }
                    case FAIL -> { fail++; counts.get(m)[1]++; }
                    case EMPTY -> { empty++; counts.get(m)[2]++; }
                    default -> { }
                }
            }
            if (any) reported++;
            if (pass == Metric.values().length) allPass++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("processId", p.id());
            row.put("name", p.name());
            row.put("owners", owners.getOrDefault(p.id(), List.of()).stream().map(o -> o.get("username")).toList());
            row.put("cells", cells);
            row.put("pass", pass);
            row.put("fail", fail);
            row.put("empty", empty);
            row.put("reported", any);
            rows.add(row);
        }

        List<Map<String, Object>> metricSummary = new ArrayList<>();
        int totalPass = 0, totalFail = 0, totalEmpty = 0;
        for (Metric m : Metric.values()) {
            int[] c = counts.get(m);
            totalPass += c[0];
            totalFail += c[1];
            totalEmpty += c[2];
            Map<String, Object> ms = new LinkedHashMap<>();
            ms.put("metric", m.name());
            ms.put("label", m.getLabel());
            ms.put("pass", c[0]);
            ms.put("fail", c[1]);
            ms.put("empty", c[2]);
            ms.put("rate", rate(c[0], c[1]));
            metricSummary.add(ms);
        }

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("processCount", processes.size());
        overall.put("reported", reported);
        overall.put("allPass", allPass);
        overall.put("pass", totalPass);
        overall.put("fail", totalFail);
        overall.put("empty", totalEmpty);
        overall.put("rate", rate(totalPass, totalFail));

        // ---- 当月走势：每天每项指标的达标率 ----
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate d = monthStart; !d.isAfter(date); d = d.plusDays(1)) {
            Map<Metric, int[]> day = new EnumMap<>(Metric.class); // [pass, fail]
            for (Metric m : Metric.values()) day.put(m, new int[2]);
            for (ProcessStep p : processes) {
                Map<Integer, Double> v = values.getOrDefault(p.id(), Map.of()).getOrDefault(d, Map.of());
                MetricJudge.day(items.getOrDefault(p.id(), List.of()), v).forEach((m, s) -> {
                    if (s == MetricStatus.PASS) day.get(m)[0]++;
                    if (s == MetricStatus.FAIL) day.get(m)[1]++;
                });
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", d);
            int dayPass = 0, dayFail = 0;
            for (Metric m : Metric.values()) {
                int[] c = day.get(m);
                dayPass += c[0];
                dayFail += c[1];
                point.put(m.name(), rate(c[0], c[1]));
            }
            point.put("ALL", rate(dayPass, dayFail));
            trend.add(point);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("overall", overall);
        result.put("metrics", metricSummary);
        result.put("rows", rows);
        result.put("trend", trend);
        return result;
    }

    /** 达标率（0~1），一项都没有可判定的数据时返回 null，前端显示“—” */
    private static Double rate(int pass, int fail) {
        return pass + fail == 0 ? null : (double) pass / (pass + fail);
    }
}
