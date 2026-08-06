package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.file.FileWriteRequest;
import com.aiworkbench.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** 用户工作区文件操作(限定在 /workspace/users/<uid> 内,路径越权拒绝) */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public R<List<WorkspaceService.FileNode>> list(@RequestParam(defaultValue = "") String path) {
        return R.ok(workspaceService.listFiles(SecurityUtil.currentUserId(), path));
    }

    /** 递归搜索文件名(root 限定搜索范围,如技能模式传 .opencode/skills) */
    @GetMapping("/search")
    public R<List<WorkspaceService.FileNode>> search(@RequestParam(defaultValue = "") String root,
                                                      @RequestParam String q) {
        return R.ok(workspaceService.searchFiles(SecurityUtil.currentUserId(), root, q));
    }

    @GetMapping("/content")
    public R<String> content(@RequestParam String path) {
        return R.ok(workspaceService.readFile(SecurityUtil.currentUserId(), path));
    }

    /** 下载文件(二进制安全,作为附件下载) */
    @GetMapping("/download")
    public ResponseEntity<InputStreamResource> download(@RequestParam String path) {
        Path file = workspaceService.getFilePath(SecurityUtil.currentUserId(), path);
        String filename = file.getFileName().toString();
        // RFC 5987 编码,支持中文文件名
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        InputStream in;
        try {
            in = java.nio.file.Files.newInputStream(file);
        } catch (IOException e) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.INTERNAL_ERROR.getCode(), "read file failed");
        }
        // 按扩展名推导真实 MIME(如 application/pdf、image/png),
        // 让前端 fetchBlob 得到的 Blob 带正确 type,iframe 才能渲染 PDF 等;
        // Content-Disposition 仍为 attachment,直接访问也只会下载不会内联。
        MediaType mediaType = mediaTypeFor(filename);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(mediaType)
                .contentLength(file.toFile().length())
                .body(new InputStreamResource(in));
    }

    @PostMapping
    @AuditLog(action = "写文件")
    public R<Void> write(@Valid @RequestBody FileWriteRequest req) {
        workspaceService.writeFile(SecurityUtil.currentUserId(), req.getPath(), req.getContent());
        return R.ok();
    }

    /** 上传文件(multipart) */
    @PostMapping("/upload")
    @AuditLog(action = "上传文件")
    public R<Void> upload(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "dir", defaultValue = "") String dir) {
        if (file.isEmpty()) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.BAD_REQUEST.getCode(), "file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unnamed";
        }
        try (InputStream data = file.getInputStream()) {
            workspaceService.uploadFile(SecurityUtil.currentUserId(), dir, filename, data);
        } catch (IOException e) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.INTERNAL_ERROR.getCode(), "upload failed");
        }
        return R.ok();
    }

    /** 新建目录 */
    @PostMapping("/mkdir")
    @AuditLog(action = "新建目录")
    public R<Void> mkdir(@RequestParam String path) {
        workspaceService.createDirectory(SecurityUtil.currentUserId(), path);
        return R.ok();
    }

    @DeleteMapping
    @AuditLog(action = "删除文件")
    public R<Void> delete(@RequestParam String path) {
        workspaceService.deleteFile(SecurityUtil.currentUserId(), path);
        return R.ok();
    }

    /** 按文件名扩展名推导 MIME,供预览(iframe/<img>/<video>)正确渲染。
     *  覆盖常见预览类型;未命中时用 JDK 内置探测,再兜底 octet-stream。 */
    private static MediaType mediaTypeFor(String filename) {
        String ext = filename.toLowerCase();
        int dot = ext.lastIndexOf('.');
        if (dot >= 0) ext = ext.substring(dot + 1);
        String ct = switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js", "mjs" -> "application/javascript";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "txt", "log" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            default -> java.net.URLConnection.guessContentTypeFromName(filename);
        };
        return (ct != null) ? MediaType.parseMediaType(ct) : MediaType.APPLICATION_OCTET_STREAM;
    }
}
