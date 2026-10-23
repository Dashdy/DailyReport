package com.welltek.backend.config;

import com.welltek.backend.dto.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 【全局异常处理】业务异常返回提示文字；其他异常记日志，只给前端一句通用提示
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResult handleBiz(BizException e) {
        return ApiResult.fail(e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResult handleUploadSize(MaxUploadSizeExceededException e) {
        return ApiResult.fail("图片不能超过 10MB");
    }

    /** 访问不存在的地址（比如直接打开后端 8083 根路径）：返回 404，不要当成服务器出错 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResult handleNotFound(NoResourceFoundException e) {
        return ApiResult.fail("地址不存在。这里是后端接口（8083），网页请打开 8085 端口");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResult handleMethod(HttpRequestMethodNotSupportedException e) {
        return ApiResult.fail("请求方式不对：" + e.getMethod());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult handleOther(Exception e) {
        log.error("接口处理出错", e);
        return ApiResult.fail("服务器出错了，请稍后重试或联系管理员");
    }
}
