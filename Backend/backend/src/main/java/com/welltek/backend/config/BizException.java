package com.welltek.backend.config;

// 【业务异常】Service 里校验不通过直接 throw，GlobalExceptionHandler 统一转成 success=false 的 ApiResult，
// 同时让 @Transactional 回滚已经写了一半的数据。
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
