package com.welltek.backend.service;

import com.welltek.backend.config.BizException;
import com.welltek.backend.mapper.SettingMapper;
import com.welltek.backend.security.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Stream;

// 【系统设置：图片存储路径】系统管理员、管理员能改（Controller 上有 @RequireAdmin）。
// - 没设置过时用 application.properties 里的 app.upload-dir（默认是后端目录下的 uploads）；
// - 改路径前先检查：必须是完整路径、能建文件夹、能写文件；
// - 改路径后旧路径记进 UPLOAD_DIR_HISTORY，显示图片时依次在当前路径、历史路径里找，
//   所以不搬文件旧图片也不会丢；勾选“复制已有图片”则把旧路径下的图片复制到新路径。
@Service
public class SettingService {

    public static final String KEY_UPLOAD_DIR = "UPLOAD_DIR";
    public static final String KEY_UPLOAD_DIR_HISTORY = "UPLOAD_DIR_HISTORY";
    /** 数据锁定时间（HH:mm）：当天的数据到当天这个时间点锁定，之后只有系统管理员 / 管理员能改 */
    public static final String KEY_LOCK_TIME = "LOCK_TIME";
    /** 锁定开关（true / false）：关闭时不按时间锁，负责人当天全天都能填；默认关闭 */
    public static final String KEY_LOCK_ENABLED = "LOCK_ENABLED";
    /** 历史数据锁定开关（true / false）：开启时前一天及以前的数据负责人不能改；默认开启 */
    public static final String KEY_HISTORY_LOCK = "HISTORY_LOCK";
    private static final LocalTime DEFAULT_LOCK_TIME = LocalTime.of(23, 59);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired
    private SettingMapper settingMapper;

    @Autowired
    private AuditService auditService;

    @Value("${app.upload-dir:./uploads}")
    private String defaultUploadDir;

    /** 当前的图片存储路径（绝对路径） */
    public Path uploadDir() {
        String v = settingMapper.selectValue(KEY_UPLOAD_DIR);
        return normalize(v == null || v.isBlank() ? defaultUploadDir : v);
    }

    /** 找图片时依次查找的目录：当前路径 → 历史路径（新的在前）→ 默认路径 */
    public List<Path> searchDirs() {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(uploadDir());
        dirs.addAll(history());
        dirs.add(normalize(defaultUploadDir));
        return new ArrayList<>(dirs);
    }

    public Map<String, Object> storageInfo() {
        Path dir = uploadDir();
        Map<String, Object> setting = settingMapper.select(KEY_UPLOAD_DIR);
        long[] stat = countImages(dir);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uploadDir", dir.toString());
        m.put("defaultDir", normalize(defaultUploadDir).toString());
        m.put("isDefault", setting == null || setting.get("value") == null);
        m.put("exists", Files.isDirectory(dir));
        m.put("fileCount", stat[0]);
        m.put("totalBytes", stat[1]);
        try {
            FileStore store = Files.getFileStore(Files.isDirectory(dir) ? dir : dir.getRoot());
            m.put("usableBytes", store.getUsableSpace());
            m.put("totalSpaceBytes", store.getTotalSpace());
        } catch (IOException | RuntimeException e) {
            m.put("usableBytes", null);
            m.put("totalSpaceBytes", null);
        }
        m.put("history", history().stream().map(Path::toString).toList());
        m.put("updatedBy", setting == null ? null : setting.get("updatedBy"));
        m.put("updatedAt", setting == null ? null : setting.get("updatedAt"));
        return m;
    }

    /** 保存新的存储路径，返回给用户看的结果说明 */
    public String saveUploadDir(LoginUser op, String input, boolean migrate) {
        if (input == null || input.isBlank()) {
            throw new BizException("请填写存储路径");
        }
        Path raw = Path.of(input.trim());
        if (!raw.isAbsolute()) {
            throw new BizException("请填写完整路径，例如 D:\\DailyReport\\uploads");
        }
        Path target = raw.normalize();
        Path old = uploadDir();
        if (target.equals(old)) {
            throw new BizException("路径没有变化");
        }
        checkWritable(target);

        int copied = 0;
        if (migrate) {
            for (Path dir : searchDirs()) {
                if (!dir.equals(target)) {
                    copied += copyImages(dir, target);
                }
            }
        }

        LinkedHashSet<Path> hist = new LinkedHashSet<>();
        hist.add(old);
        hist.addAll(history());
        hist.remove(target);
        settingMapper.upsert(KEY_UPLOAD_DIR_HISTORY, String.join("\n", hist.stream().map(Path::toString).toList()), op.username());
        settingMapper.upsert(KEY_UPLOAD_DIR, target.toString(), op.username());

        auditService.log(op, "系统设置", "修改", null, "图片存储路径", old.toString(),
                target + (migrate ? "（已复制 " + copied + " 张旧图片）" : ""));
        return migrate
                ? "存储路径已修改，已把 " + copied + " 张旧图片复制到新路径"
                : "存储路径已修改，新上传的图片会存到新路径，旧图片仍从原路径读取";
    }

    // ---------------- 数据锁定时间 ----------------

    public LocalTime lockTime() {
        String v = settingMapper.selectValue(KEY_LOCK_TIME);
        try {
            return v == null || v.isBlank() ? DEFAULT_LOCK_TIME : LocalTime.parse(v, HH_MM);
        } catch (DateTimeParseException e) {
            return DEFAULT_LOCK_TIME;
        }
    }

    /**
     * 工序负责人现在还能不能填 / 改今天的数据。
     * 锁定开关关闭 → 全天都能；开启 → 到当天锁定时间为止。例：锁定时间 20:00，19:30 能改，20:00 起不能。
     */
    public boolean ownerTodayEditable() {
        return !lockEnabled() || LocalTime.now().isBefore(lockTime());
    }

    /** 工序负责人能不能改某一天的数据（未来日期另由调用方拦截） */
    public boolean ownerCanEdit(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.isBefore(today)) {
            return !historyLockEnabled();
        }
        return date.equals(today) && ownerTodayEditable();
    }

    public boolean lockEnabled() {
        return "true".equals(settingMapper.selectValue(KEY_LOCK_ENABLED));
    }

    /** 历史数据锁定（前一天及以前对负责人锁定），默认开启 */
    public boolean historyLockEnabled() {
        return !"false".equals(settingMapper.selectValue(KEY_HISTORY_LOCK));
    }

    /** 系统管理员、管理员都能拨开关（Controller 上是 @RequireAdmin） */
    public void saveHistoryLock(LoginUser op, boolean enabled) {
        boolean old = historyLockEnabled();
        if (old == enabled) {
            throw new BizException(enabled ? "历史数据锁定已经是开启状态" : "历史数据锁定已经是关闭状态");
        }
        settingMapper.upsert(KEY_HISTORY_LOCK, String.valueOf(enabled), op.username());
        auditService.log(op, "系统设置", enabled ? "开启" : "关闭", null, "历史数据锁定开关",
                old ? "开启（负责人不能改以前的数据）" : "关闭（负责人可以改以前的数据）",
                enabled ? "开启（负责人不能改以前的数据）" : "关闭（负责人可以改以前的数据）");
    }

    /** 系统管理员、管理员都能拨开关（Controller 上是 @RequireAdmin） */
    public void saveLockEnabled(LoginUser op, boolean enabled) {
        boolean old = lockEnabled();
        if (old == enabled) {
            throw new BizException(enabled ? "锁定已经是开启状态" : "锁定已经是关闭状态");
        }
        settingMapper.upsert(KEY_LOCK_ENABLED, String.valueOf(enabled), op.username());
        auditService.log(op, "系统设置", enabled ? "开启" : "关闭", null, "数据锁定开关",
                old ? "开启" : "关闭", (enabled ? "开启（每天 " : "关闭（原定 ") + lockTime().format(HH_MM) + (enabled ? " 锁定当天数据）" : " 锁定，现在当天全天可填）"));
    }

    public Map<String, Object> lockInfo() {
        Map<String, Object> setting = settingMapper.select(KEY_LOCK_TIME);
        Map<String, Object> enabledSetting = settingMapper.select(KEY_LOCK_ENABLED);
        Map<String, Object> historySetting = settingMapper.select(KEY_HISTORY_LOCK);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("historyLock", historyLockEnabled());
        m.put("historyUpdatedBy", historySetting == null ? null : historySetting.get("updatedBy"));
        m.put("historyUpdatedAt", historySetting == null ? null : historySetting.get("updatedAt"));
        m.put("enabled", lockEnabled());
        m.put("enabledUpdatedBy", enabledSetting == null ? null : enabledSetting.get("updatedBy"));
        m.put("enabledUpdatedAt", enabledSetting == null ? null : enabledSetting.get("updatedAt"));
        m.put("lockTime", lockTime().format(HH_MM));
        m.put("isDefault", setting == null || setting.get("value") == null);
        m.put("ownerTodayEditable", ownerTodayEditable());
        m.put("updatedBy", setting == null ? null : setting.get("updatedBy"));
        m.put("updatedAt", setting == null ? null : setting.get("updatedAt"));
        return m;
    }

    /** 只有系统管理员能改（Controller 上有 @RequireSysAdmin） */
    public void saveLockTime(LoginUser op, String input) {
        LocalTime t;
        try {
            t = LocalTime.parse(input == null ? "" : input.trim(), HH_MM);
        } catch (DateTimeParseException e) {
            throw new BizException("锁定时间格式不对，请填写 HH:mm，例如 08:00");
        }
        String old = lockTime().format(HH_MM);
        String next = t.format(HH_MM);
        if (old.equals(next)) {
            throw new BizException("锁定时间没有变化");
        }
        settingMapper.upsert(KEY_LOCK_TIME, next, op.username());
        auditService.log(op, "系统设置", "修改", null, "数据锁定时间", "当天 " + old + " 锁定", "当天 " + next + " 锁定");
    }

    // ---------------- 内部方法 ----------------

    private List<Path> history() {
        String v = settingMapper.selectValue(KEY_UPLOAD_DIR_HISTORY);
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split("\n")).filter(s -> !s.isBlank()).map(SettingService::normalize).toList();
    }

    private static Path normalize(String dir) {
        return Path.of(dir).toAbsolutePath().normalize();
    }

    /** 建文件夹并写一个测试文件，失败就告诉用户原因 */
    private static void checkWritable(Path dir) {
        Path root = dir.getRoot();
        if (root != null && !Files.exists(root)) {
            throw new BizException("磁盘 " + root + " 不存在，请检查盘符");
        }
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-test-" + UUID.randomUUID());
            Files.writeString(probe, "ok");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            throw new BizException("这个路径不能使用：" + e.getMessage() + "。请确认磁盘存在、后端程序有写入权限");
        }
    }

    /** 复制 dir 下 yyyyMM/xxx.ext 结构的图片，目标已有同名文件则跳过 */
    private static int copyImages(Path from, Path to) {
        if (!Files.isDirectory(from)) {
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> months = Files.list(from)) {
            for (Path month : months.filter(p -> Files.isDirectory(p) && p.getFileName().toString().matches("\\d{6}")).toList()) {
                Path destMonth = to.resolve(month.getFileName().toString());
                Files.createDirectories(destMonth);
                try (Stream<Path> files = Files.list(month)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        Path dest = destMonth.resolve(f.getFileName().toString());
                        if (!Files.exists(dest)) {
                            Files.copy(f, dest);
                            count[0]++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new BizException("复制旧图片失败：" + e.getMessage());
        }
        return count[0];
    }

    /** [图片数量, 总字节数] */
    private static long[] countImages(Path dir) {
        long[] r = {0, 0};
        if (!Files.isDirectory(dir)) {
            return r;
        }
        try (Stream<Path> s = Files.walk(dir, 2)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                r[0]++;
                try {
                    r[1] += Files.size(f);
                } catch (IOException ignored) {
                    // 统计用，个别文件读不到大小不影响
                }
            });
        } catch (IOException ignored) {
            // 统计用
        }
        return r;
    }
}
