package com.welltek.backend.service;

import com.welltek.backend.entity.DailyRecord;
import com.welltek.backend.entity.Metric;
import com.welltek.backend.entity.MetricItem;
import com.welltek.backend.entity.MetricStatus;

import java.util.*;

// 【按子项判定一道工序某一天的六项结果】首页、选项卡、留底、推送共用，保证各处红绿一致。
// 每个子项单独判定；一项指标的所有子项都达标，这项才算达标（规则见 MetricStatus.combine）。
public final class MetricJudge {

    private MetricJudge() {
    }

    /** 子项按指标分组（指标按六项顺序，没有子项的指标是空列表） */
    public static Map<Metric, List<MetricItem>> group(List<MetricItem> items) {
        Map<Metric, List<MetricItem>> map = new EnumMap<>(Metric.class);
        for (Metric m : Metric.values()) map.put(m, new ArrayList<>());
        for (MetricItem item : items) map.get(item.metric()).add(item);
        return map;
    }

    /** 某天的数据：itemId → 值 */
    public static Map<Integer, Double> values(Collection<DailyRecord> records) {
        Map<Integer, Double> map = new HashMap<>();
        for (DailyRecord r : records) map.put(r.itemId(), r.value());
        return map;
    }

    /** 一项指标的结果 */
    public static MetricStatus metric(List<MetricItem> items, Map<Integer, Double> values) {
        List<MetricStatus> list = new ArrayList<>();
        for (MetricItem item : items) list.add(item.judge(values.get(item.id())));
        return MetricStatus.combine(list);
    }

    /** 一个子项的目标，如“≤ 0.22%”“≥ 2000 ㎡”；没设目标值时为“未设” */
    public static String ruleText(MetricItem i) {
        if (i.targetValue() == null) return "未设";
        String unit = i.unit() == null || i.unit().isBlank() ? "" : "%".equals(i.unit()) ? "%" : " " + i.unit();
        return ("GE".equals(i.compareOp()) ? "≥ " : "≤ ")
                + java.math.BigDecimal.valueOf(i.targetValue()).stripTrailingZeros().toPlainString() + unit;
    }

    /**
     * “班组自定”目标（2026-10-24 起由子项自动生成，不再单独填文字）：
     * 一个子项 → “≥ 2000 ㎡”；拆了子项 → “铜球 ≤ 0.22%；锡球 ≤ 0.06%”
     */
    public static String targetText(List<MetricItem> items) {
        if (items.isEmpty()) return "—";
        if (items.size() == 1) return ruleText(items.get(0));
        StringJoiner sj = new StringJoiner("；");
        for (MetricItem i : items) sj.add(i.name() + " " + ruleText(i));
        return sj.toString();
    }

    /** 六项指标各自的结果 */
    public static Map<Metric, MetricStatus> day(List<MetricItem> items, Map<Integer, Double> values) {
        Map<Metric, MetricStatus> result = new EnumMap<>(Metric.class);
        group(items).forEach((m, list) -> result.put(m, metric(list, values)));
        return result;
    }
}
