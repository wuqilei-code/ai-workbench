package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.TaskInfoVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 后台任务状态查询/清理:tasks/&lt;id&gt;.{json,pid,log,done,delivered} 运行文件聚合。
 * 与 {@link BackgroundTaskWatcher} 共用同一约定:json 为任务卡、pid 判活、done 为状态(以 failed 开头即失败)、
 * delivered 为已续跑交付标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final WorkspaceService workspaceService;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    /** 列表短尾字符数 */
    private static final int LIST_TAIL = 600;
    /** 日志详情默认尾部字符数 */
    private static final int LOG_TAIL_DEFAULT = 8000;

    /** 该用户任务目录(相对工作区根:tasks)。 */
    private Path tasksDir(Long userId) {
        return workspaceService.resolveWritePath(userId, "tasks");
    }

    /** 校验任务 id 合法且落在用户 tasks/ 目录下,返回该任务各运行文件的解析路径。 */
    private Path taskFile(Long userId, String id, String ext) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return workspaceService.resolveWritePath(userId, "tasks/" + id + "." + ext);
    }

    /** 后台任务列表(按发起时间倒序)。 */
    public List<TaskInfoVo> listTasks(Long userId) {
        Path dir = tasksDir(userId);
        List<TaskInfoVo> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> SAFE_ID.matcher(stripSuffix(p.getFileName().toString())).matches())
                    .forEach(p -> {
                        try {
                            result.add(build(userId, stripSuffix(p.getFileName().toString())));
                        } catch (Exception e) {
                            log.debug("build task view failed for {}: {}", p, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.debug("list tasks failed for user {}: {}", userId, e.toString());
        }
        result.sort(Comparator.comparingLong(TaskInfoVo::getStartedAt).reversed());
        return result;
    }

    private TaskInfoVo build(Long userId, String id) {
        Path json = taskFile(userId, id, "json");
        long startedAt;
        try {
            startedAt = Files.getLastModifiedTime(json).toMillis();
        } catch (IOException e) {
            startedAt = 0L;
        }
        Path done = taskFile(userId, id, "done");
        Path delivered = taskFile(userId, id, "delivered");
        Path pid = taskFile(userId, id, "pid");

        String doneContent = null;
        boolean hasDone = Files.isRegularFile(done);
        if (hasDone) {
            try {
                doneContent = Files.readString(done).trim();
            } catch (IOException e) {
                doneContent = "任务完成";
            }
        }
        boolean isDelivered = Files.isRegularFile(delivered);
        boolean failed = doneContent != null && doneContent.startsWith("failed");

        String status;
        String statusText;
        Long finishedAt = null;
        Long runningSeconds = null;
        boolean running = false;

        if (isDelivered) {
            status = "delivered";
            statusText = doneContent == null || doneContent.isBlank() ? "已交付" : doneContent;
            finishedAt = mtime(delivered);
        } else if (hasDone) {
            if (failed) {
                status = "failed";
                statusText = doneContent == null || doneContent.isBlank() ? "失败" : doneContent;
            } else {
                status = "done";
                statusText = doneContent == null || doneContent.isBlank() ? "任务完成" : doneContent;
            }
            finishedAt = mtime(done);
        } else if (Files.isRegularFile(pid)) {
            boolean alive = readPidAlive(pid);
            if (alive) {
                running = true;
                status = "running";
                statusText = "运行中";
            } else {
                status = "done";
                statusText = "任务完成(进程已退出)";
                finishedAt = mtime(pid);
            }
        } else {
            status = "pending";
            statusText = "等待启动";
        }

        long now = System.currentTimeMillis();
        if (running) {
            runningSeconds = Math.max(0, (now - startedAt) / 1000);
        } else if (finishedAt != null && startedAt > 0) {
            runningSeconds = Math.max(0, (finishedAt - startedAt) / 1000);
        }

        return TaskInfoVo.builder()
                .id(id)
                .status(status)
                .statusText(statusText)
                .doneContent(doneContent)
                .delivered(isDelivered)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .runningSeconds(runningSeconds)
                .logTail(tail(taskFile(userId, id, "log"), LIST_TAIL))
                .build();
    }

    /** 任务日志尾部。 */
    public String taskLog(Long userId, String id, Integer tail) {
        int n = (tail == null || tail < 0) ? LOG_TAIL_DEFAULT : tail;
        return tail(taskFile(userId, id, "log"), Math.min(n, 100_000));
    }

    /** 清理任务运行文件(json/log/pid/done/delivered);脚本等其它文件保留。 */
    public void cleanupTask(Long userId, String id) {
        String[] exts = { "json", "log", "pid", "done", "delivered" };
        for (String ext : exts) {
            Path p = taskFile(userId, id, ext);
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.debug("cleanup task file {} failed: {}", p, e.toString());
            }
        }
    }

    // ---- 内部 ----

    private static boolean readPidAlive(Path pid) {
        try {
            long pidValue = Long.parseLong(Files.readString(pid).trim());
            return ProcessHandle.of(pidValue).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static Long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /** 读取文件尾部(UTF-8),最多 maxChars 字符。 */
    private static String tail(Path p, int maxChars) {
        if (!Files.isRegularFile(p)) {
            return "";
        }
        try {
            long size = Files.size(p);
            long readFrom = Math.max(0, size - (maxChars * 4L + 4096L));
            byte[] buf;
            try (SeekableByteChannel ch = Files.newByteChannel(p, StandardOpenOption.READ)) {
                ch.position(readFrom);
                ByteBuffer bb = ByteBuffer.allocate((int) (size - readFrom));
                while (bb.hasRemaining() && ch.read(bb) >= 0) {
                    // read
                }
                bb.flip();
                buf = new byte[bb.limit()];
                bb.get(buf);
            }
            String s = new String(buf, StandardCharsets.UTF_8);
            return s.length() <= maxChars ? s : s.substring(s.length() - maxChars);
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripSuffix(String name) {
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
