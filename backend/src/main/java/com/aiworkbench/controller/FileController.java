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

import jakarta.servlet.http.HttpServletRequest;

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

    /** 批量存在性检查:返回仍存在于工作区的路径子集(结果文件栏过滤"已清理"文件用)。
     *  越权/非法路径一律视为不存在,不抛异常。 */
    @GetMapping("/exists")
    public R<List<String>> exists(@RequestParam("paths") List<String> paths) {
        var uid = SecurityUtil.currentUserId();
        List<String> existing = new java.util.ArrayList<>();
        if (paths != null) {
            for (String p : paths) {
                if (p == null || p.isBlank()) continue;
                try {
                    if (java.nio.file.Files.exists(workspaceService.getFilePath(uid, p))) {
                        existing.add(p);
                    }
                } catch (Exception ignored) {
                    // 越权/非法路径视为不存在
                }
            }
        }
        return R.ok(existing);
    }

    @GetMapping("/content")
    public R<String> content(@RequestParam String path) {
        return R.ok(workspaceService.readFile(SecurityUtil.currentUserId(), path));
    }

    /** 内联返回文件内容(Content-Disposition: inline + 正确 MIME),
     *  供 HTML 预览 iframe 及其相对子资源(图表/样式/脚本)直接引用:
     *  iframe src=/api/files/raw/reports/x.html 时,内嵌的相对路径
     *  charts/a.png 会天然解析到 /api/files/raw/reports/charts/a.png,
     *  同源 HttpOnly cookie 自动完成鉴权。
     *  未命中(路径被 HTML 引用触发而非页面主体)同样返回 inline 内容。 */
    @GetMapping("/raw/**")
    public ResponseEntity<InputStreamResource> raw(HttpServletRequest request) {
        String raw = request.getRequestURI();
        String prefix = "/api/files/raw/";
        String path = (raw.startsWith(prefix)) ? raw.substring(prefix.length()) : raw;
        path = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8);
        Path file = workspaceService.getFilePath(SecurityUtil.currentUserId(), path);
        String filename = file.getFileName().toString();
        InputStream in;
        try {
            in = java.nio.file.Files.newInputStream(file);
        } catch (IOException e) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.INTERNAL_ERROR.getCode(), "read file failed");
        }
        // inline + 正确 MIME:浏览器直接渲染,不触发下载
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20") + "\"")
                .contentType(mediaTypeFor(filename))
                .contentLength(file.toFile().length())
                .body(new InputStreamResource(in));
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

    /** 上传 ZIP 项目包并解压到指定目录(默认 uploads/),返回解压文件数 */
    @PostMapping("/upload-zip")
    @AuditLog(action = "上传项目包")
    public R<Integer> uploadZip(@RequestParam("file") MultipartFile file,
                                @RequestParam(value = "dir", defaultValue = "") String dir) {
        if (file.isEmpty()) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.BAD_REQUEST.getCode(), "file is empty");
        }
        try (InputStream data = file.getInputStream()) {
            int count = workspaceService.uploadZip(SecurityUtil.currentUserId(), dir, data);
            return R.ok(count);
        } catch (IOException e) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.INTERNAL_ERROR.getCode(), "upload zip failed");
        }
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
            case "html", "htm" -> "text/html; charset=utf-8";
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
