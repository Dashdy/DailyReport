package com.welltek.backend.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.welltek.backend.entity.ProcessBoard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;

// 【班组看板】dbo.PROCESS_BOARD，每道工序一行；四个人存成 JSON 放在 MEMBERS 列
@Repository
public class BoardMapper {

    private static final TypeReference<List<ProcessBoard.Member>> MEMBERS_TYPE = new TypeReference<>() {
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public ProcessBoard selectByProcess(Integer processId) {
        List<ProcessBoard> list = jdbcTemplate.query(
                "SELECT PROCESS_ID, MEMBERS, SIGNERS, SIGN_DATE, UPDATED_BY, UPDATED_AT FROM dbo.PROCESS_BOARD WHERE PROCESS_ID = ?",
                (rs, i) -> new ProcessBoard(
                        rs.getInt("PROCESS_ID"),
                        parseMembers(rs.getString("MEMBERS")),
                        rs.getString("SIGNERS"),
                        RowUtils.date(rs, "SIGN_DATE"),
                        rs.getString("UPDATED_BY"),
                        RowUtils.dateTime(rs, "UPDATED_AT")),
                processId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 有就更新，没有就新建 */
    public void upsert(ProcessBoard b) {
        Date signDate = b.signDate() == null ? null : Date.valueOf(b.signDate());
        String members = toJson(b.members());
        int updated = jdbcTemplate.update("UPDATE dbo.PROCESS_BOARD SET MEMBERS = ?, SIGNERS = ?, SIGN_DATE = ?, " +
                        "UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() WHERE PROCESS_ID = ?",
                members, b.signers(), signDate, b.updatedBy(), b.processId());
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO dbo.PROCESS_BOARD (PROCESS_ID, MEMBERS, SIGNERS, SIGN_DATE, UPDATED_BY, UPDATED_AT) " +
                            "VALUES (?, ?, ?, ?, ?, SYSDATETIME())",
                    b.processId(), members, b.signers(), signDate, b.updatedBy());
        }
    }

    private List<ProcessBoard.Member> parseMembers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, MEMBERS_TYPE);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String toJson(List<ProcessBoard.Member> members) {
        try {
            return objectMapper.writeValueAsString(members == null ? List.of() : members);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
