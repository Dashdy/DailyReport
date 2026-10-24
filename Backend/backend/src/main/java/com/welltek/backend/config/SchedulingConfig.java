package com.welltek.backend.config;

import com.welltek.backend.service.PushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

// 【定时任务】每分钟看一眼是不是到了企业微信“自动推送时间”（时间在系统设置里配置）
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Autowired
    private PushService pushService;

    @Scheduled(cron = "0 * * * * *")
    public void pushTick() {
        pushService.autoTick();
    }
}
