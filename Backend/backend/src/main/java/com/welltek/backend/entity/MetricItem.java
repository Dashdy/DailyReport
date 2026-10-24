package com.welltek.backend.entity;

import java.time.LocalDateTime;

// 【指标子项】对应 dbo.METRIC_ITEM
// 每项指标至少一个子项，可以拆成多个（如 成本 → 锡球、铜球），每个子项单独填数、单独判定红绿。
// 只有一个子项时 name 为空，页面上直接显示指标名。
public record MetricItem(
        Integer id,
        Integer processId,
        Metric metric,
        String name,
        String unit,
        String compareOp,
        Double targetValue,
        int sortOrder,
        boolean active,
        String updatedBy,
        LocalDateTime updatedAt
) {

    /** 给人看的名字：“成本”或“成本·锡球” */
    public String label() {
        return name == null || name.isBlank() ? metric.getLabel() : metric.getLabel() + "·" + name;
    }

    public MetricStatus judge(Double value) {
        return MetricStatus.judge(value, compareOp, targetValue);
    }
}
