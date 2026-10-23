package com.welltek.backend.entity;

import java.time.LocalDateTime;

// 【六项自主保证书的一行】对应 dbo.METRIC_TARGET
// baseline / teamTarget 是给人看的文字（如“≤ 3%”“本班 320 片”），
// compareOp + targetValue 是系统用来判定当天达不达标的数值规则。
public record MetricTarget(
        Integer processId,
        Metric metric,
        String baseline,
        String teamTarget,
        String unit,
        String compareOp,
        Double targetValue,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
