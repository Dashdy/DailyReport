package com.welltek.backend.dto;

// 【通用响应】跟 Dashboard 项目同一个约定：success + message，需要带数据时放在 data 里。
// 业务上的失败（没权限编辑这个工序、用户名重复……）返回 HTTP 200 + success=false，
// 前端统一看 success 字段弹提示；只有“没登录 / 登录过期”才返回 401。
public class ApiResult {

    private boolean success;
    private String message;
    private Object data;

    public static ApiResult ok(String message) {
        return ok(message, null);
    }

    public static ApiResult ok(String message, Object data) {
        ApiResult result = new ApiResult();
        result.success = true;
        result.message = message;
        result.data = data;
        return result;
    }

    public static ApiResult fail(String message) {
        ApiResult result = new ApiResult();
        result.success = false;
        result.message = message;
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }
}
