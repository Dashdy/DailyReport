package com.welltek.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 【班组看板】对应 dbo.PROCESS_BOARD：四功能小组名字&照片 / 本组三条动作 / 组员签名照片栏 / 全员签名
public record ProcessBoard(
        Integer processId,
        String groupName,
        String groupPhoto,
        String action1,
        String action2,
        String action3,
        String memberPhoto,
        String signers,
        LocalDate signDate,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
