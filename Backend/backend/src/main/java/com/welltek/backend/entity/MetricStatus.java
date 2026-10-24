package com.welltek.backend.entity;

import java.util.Collection;

// 【达标判定结果】
public enum MetricStatus {
    PASS,     // 达标
    FAIL,     // 未达标
    EMPTY,    // 当天没填（拆了子项的指标：有子项没填也算没填完）
    NO_TARGET; // 填了数，但还没设定判定目标值

    // COMPARE_OP：LE 表示当前值 ≤ 目标值为达标（安全、报废、成本、设备停机这类越小越好），
    //             GE 表示当前值 ≥ 目标值为达标（交付这类越大越好）
    public static MetricStatus judge(Double value, String compareOp, Double targetValue) {
        if (value == null) {
            return EMPTY;
        }
        if (targetValue == null) {
            return NO_TARGET;
        }
        boolean pass = "GE".equals(compareOp) ? value >= targetValue : value <= targetValue;
        return pass ? PASS : FAIL;
    }

    /**
     * 一项指标的结果 = 各子项结果合起来：所有子项都达标才算达标。
     * 有一个未达标 → 未达标；否则有没填的 → 未填（没填完）；否则有没设目标的 → 未设目标。
     */
    public static MetricStatus combine(Collection<MetricStatus> items) {
        if (items.isEmpty()) return EMPTY;
        if (items.contains(FAIL)) return FAIL;
        if (items.contains(EMPTY)) return EMPTY;
        if (items.contains(NO_TARGET)) return NO_TARGET;
        return PASS;
    }
}
