package com.welltek.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 【每日行动与亮点】对应 dbo.DAILY_NOTE：某工序某天的 今日行动措施 / 今日亮点 / 明日行动计划
public record DailyNote(
        Integer processId,
        LocalDate reportDate,
        String actions,
        String highlights,
        String plans,
        String updatedBy,
        LocalDateTime updatedAt
) {

    public boolean isEmpty() {
        return actions == null && highlights == null && plans == null;
    }
}
