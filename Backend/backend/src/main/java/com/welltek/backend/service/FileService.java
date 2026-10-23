package com.welltek.backend.service;

import com.welltek.backend.config.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

// 【图片上传】班组照片、组员签名照片。存到“系统设置”里配置的存储路径下，按月份分目录，
// 文件名用随机 UUID，返回 /uploads/yyyyMM/xxx.jpg，由 UploadFileController 负责显示。
@Service
public class FileService {

    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    @Autowired
    private SettingService settingService;

    public String saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(ext)) {
            throw new BizException("只支持 jpg / png / gif / webp / bmp 格式的图片");
        }

        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = settingService.uploadDir().resolve(month);
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(name));
        } catch (IOException e) {
            throw new BizException("图片保存失败：" + e.getMessage() + "。请联系系统管理员检查“系统设置”里的存储路径");
        }
        return "/uploads/" + month + "/" + name;
    }
}
