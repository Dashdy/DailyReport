package com.welltek.backend.controller;

import com.welltek.backend.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// 【显示上传的图片】/uploads/yyyyMM/文件名
// 依次在当前存储路径、历史存储路径里找，系统管理员改了存储路径后旧图片照样能显示。
// 路径变量用正则限死成“6 位月份 + 32 位文件名 + 扩展名”，不会被 ../ 之类的路径读到别的文件。
@RestController
public class UploadFileController {

    @Autowired
    private SettingService settingService;

    @GetMapping("/uploads/{month:\\d{6}}/{name:[0-9a-f]{32}\\.(?:jpg|jpeg|png|gif|webp|bmp)}")
    public ResponseEntity<FileSystemResource> image(@PathVariable String month, @PathVariable String name) {
        for (Path dir : settingService.searchDirs()) {
            Path file = dir.resolve(month).resolve(name);
            if (Files.isRegularFile(file)) {
                MediaType type = MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM);
                return ResponseEntity.ok()
                        .contentType(type)
                        .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS)) // 文件名是随机的，内容不会变
                        .body(new FileSystemResource(file));
            }
        }
        return ResponseEntity.notFound().build();
    }
}
