package com.welltek.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 【每日走势表的一格】对应 dbo.DAILY_RECORD
// createdBy 是首次填报人，updatedBy 是最后保存人
public record DailyRecord(
        Long id,
        Integer processId,
        LocalDate reportDate,
        Metric metric,
        Double value,
        String createdBy,
        LocalDateTime createdAt,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
