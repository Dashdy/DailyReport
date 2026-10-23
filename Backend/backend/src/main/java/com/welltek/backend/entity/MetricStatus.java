package com.welltek.backend.entity;

// 【达标判定结果】
public enum MetricStatus {
    PASS,     // 达标
    FAIL,     // 未达标
    EMPTY,    // 当天没填
    NO_TARGET; // 填了数，但这项还没设定判定目标值

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
}
