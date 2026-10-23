package com.welltek.backend.entity;

// 【六项自主保证指标】顺序就是页面上的显示顺序
public enum Metric {
    SAFETY("安全"),
    QUALITY("品质"),
    DELIVERY("交付"),
    SCRAP("报废"),
    COST("成本"),
    EQUIPMENT("设备");

    private final String label;

    Metric(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
