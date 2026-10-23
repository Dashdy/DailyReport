package com.welltek.backend.config;

import com.welltek.backend.entity.Role;
import com.welltek.backend.mapper.ProcessMapper;
import com.welltek.backend.mapper.UserMapper;
import com.welltek.backend.service.ProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

// 【初始数据】启动时检查一遍，已经存在的不会被覆盖：
// 0. 老库结构升级（SchemaMigrator）；
// 1. 用户表为空时（第一次部署）创建初始系统管理员“系统管理员 / admin”、初始管理员“叶丰龙 / 0625”（账号 / 密码）；
// 2. 工序表为空时写入 25 道默认工序（之后在“工序配置”页面增删改，不会再被重新写入）；
// 3. 每道工序补齐六项自主保证书的默认目标。
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final List<String> DEFAULT_PROCESSES = List.of(
            "开料", "内层图形", "内层AOI", "棕化", "压合", "钻孔", "沉铜", "VCP电镀", "树脂塞孔", "树脂塞孔AOI",
            "外层图形", "外层AOI", "防焊", "文字", "化金", "金手指", "喷锡", "成型", "验孔", "电测",
            "FQC", "OSP", "沉锡", "FQA", "包装");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProcessMapper processMapper;

    @Autowired
    private ProcessService processService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SchemaMigrator schemaMigrator;

    @Override
    public void run(String... args) {
        schemaMigrator.migrate();

        // 初始账号：只在用户表完全为空（第一次部署）时创建。
        // 不能按“账号不存在就补建”——管理员把初始账号改了名之后，每次重启都会凭空多出一个
        // 默认密码的系统管理员（2026-10-23 踩过：改名后多出了一个账号“系统管理员 / admin”）。
        if (userMapper.selectAll().isEmpty()) {
            createUser("系统管理员", "系统管理员", "admin", Role.SYS_ADMIN);
            createUser("叶丰龙", "叶丰龙", "0625", Role.ADMIN);
        }

        if (processMapper.count() == 0) {
            for (int i = 0; i < DEFAULT_PROCESSES.size(); i++) {
                processMapper.insert(DEFAULT_PROCESSES.get(i), (i + 1) * 10);
            }
            log.info("已写入 {} 道默认工序", DEFAULT_PROCESSES.size());
        }

        processMapper.selectAll(true).forEach(p -> processService.ensureTargets(p.id()));
    }

    private void createUser(String account, String username, String rawPassword, Role role) {
        userMapper.insert(account, username, passwordEncoder.encode(rawPassword), role, true, "系统初始账号");
        log.info("已创建初始账号：{}（{}）", account, role.getLabel());
    }
}
