package com.welltek.backend.entity;

import java.time.LocalDateTime;

// 【六项自主保证书的一行】对应 dbo.METRIC_TARGET
// baseline / teamTarget 是给人看的文字（如“≤ 3%”“锡球 0.06 铜球 0.22”）；
// 系统判定红绿用的数值规则在子项里（MetricItem）。
public record MetricTarget(
        Integer processId,
        Metric metric,
        String baseline,
        String teamTarget,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
