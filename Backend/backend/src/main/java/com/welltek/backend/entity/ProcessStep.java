package com.welltek.backend.entity;

// 【工序】对应 dbo.PROCESS_STEP
// 停用（active=false）：还在工序配置里，不出现在选项卡和首页；
// 删除（deleted=true）：各个页面都不再显示，但历史战报、修改记录都留在库里，可以恢复。
public record ProcessStep(
        Integer id,
        String name,
        int sortOrder,
        boolean active,
        boolean deleted
) {
}
