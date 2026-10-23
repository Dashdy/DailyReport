package com.welltek.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 【班组看板】对应 dbo.PROCESS_BOARD：四功能小组四个人（照片、姓名、职能）+ 全员签名 + 日期
public record ProcessBoard(
        Integer processId,
        List<Member> members,
        String signers,
        LocalDate signDate,
        String updatedBy,
        LocalDateTime updatedAt
) {

    /** 小组里的一个人：照片地址、姓名、职能（如“安全”） */
    public record Member(String photo, String name, String role) {
    }
}
