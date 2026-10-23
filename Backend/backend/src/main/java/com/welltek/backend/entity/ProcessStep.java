package com.welltek.backend.entity;

// 【工序】对应 dbo.PROCESS_STEP；停用（active=false）而不删除，历史战报照样能查
public record ProcessStep(
        Integer id,
        String name,
        int sortOrder,
        boolean active
) {
}
