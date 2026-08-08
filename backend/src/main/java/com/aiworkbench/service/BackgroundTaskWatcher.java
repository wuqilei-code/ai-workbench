package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.dto.PromptAsyncBody;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 后台长任务完成后自动续跑:技能侧按约定把长任务放后台
 * (tasks/&lt;id&gt;.json + &lt;id&gt;.pid + &lt;id&gt;.log,nohup ... &),立即结束本轮;
 * 本组件定期扫描各用户 tasks/ 目录,任务结束后把运行结果作为新消息注入用户最近会话,
 * 让 agent 依据结果自动继续后续操作。
 * <p>
 * 完成判定:<id>.done 存在(状态文件),或 <id>.pid 指向的进程已退出;
 * 交付一次后写 <id>.delivered 去重。目标会话 busy 时顺延下一轮,避免与用户手发消息打架。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundTaskWatcher {

    private final OpencodeProperties props;
    private final WorkspaceService workspaceService;
    private final ChatSessionService chatSessionService;
    private final OpencodeClient opencodeClient;
    private final OpencodeProcessManager processManager;
    private final ObjectMapper objectMapper;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    /** 未完成且无 pid 的任务超过该时长则放弃(写 delivered 停止重试),防止坏文件无限重扫。 */
    private static final long STALE_MS = 60L * 60 * 1000;

    @Scheduled(fixedDelayString = "${opencode.background-task-poll-ms:5000}")
    public void sweep() {
        Path root = Paths.get(props.getWorkspaceRoot(), "users");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> users = Files.list(root)) {
            users.filter(Files::isDirectory).forEach(userDir -> {
                Long userId = parseUserId(userDir.getFileName().toString());
                if (userId == null) {
                    return;
                }
                Path tasksDir = userDir.resolve("project").resolve("tasks");
                if (!Files.isDirectory(tasksDir)) {
                    return;
                }
                try (Stream<Path> files = Files.list(tasksDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .filter(p -> SAFE_ID.matcher(stripSuffix(p.getFileName().toString())).matches())
                            .forEach(f -> {
                                try {
                                    processTask(userId, tasksDir, f);
                                } catch (Exception e) {
                                    log.debug("background task {} failed: {}", f, e.toString());
                                }
                            });
                } catch (Exception e) {
                    log.debug("scan tasks dir {} failed: {}", tasksDir, e.toString());
                }
            });
        } catch (Exception e) {
            log.debug("background task sweep failed: {}", e.toString());
        }
    }

    private void processTask(Long userId, Path tasksDir, Path taskFile) throws IOException {
        String id = stripSuffix(taskFile.getFileName().toString());
        Path delivered = tasksDir.resolve(id + ".delivered");
        if (Files.exists(delivered)) {
            return;
        }
        JsonNode spec;
        try {
            spec = objectMapper.readTree(Files.readAllBytes(taskFile));
        } catch (Exception e) {
            return; // json 可能正在写入,下轮再试
        }
        if (spec == null || !spec.isObject()) {
            return;
        }

        String status = finishedStatus(tasksDir, id, taskFile);
        if (status == null) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(taskFile).toMillis();
            if (age > STALE_MS) {
                log.debug("give up stale background task {} (user {})", id, userId);
                markDelivered(tasksDir, id);
            }
            return;
        }

        String logTail = tail(tasksDir.resolve(id + ".log"), props.getBackgroundTaskLogTail());

        ChatSession target = resolveSession(userId, spec);
        if (target == null || target.getOpencodeSessionId() == null) {
            log.debug("no target session for background task {} (user {})", id, userId);
            return;
        }
        if (processManager.isStreaming(userId)) {
            log.debug("user {} busy, defer background task {}", userId, id);
            return;
        }

        String continueText = spec.path("continue").asText("");
        if (continueText.isBlank()) {
            continueText = "请根据以上运行结果继续完成该任务发起时约定的后续工作,并给出最终答复。";
        }
        String prompt = "【后台任务 " + id + " 已完成】\n"
                + "运行状态:" + status + "\n"
                + "运行输出(末尾):\n"
                + (logTail.isEmpty() ? "(无输出)" : logTail) + "\n\n"
                + continueText;

        try {
            opencodeClient.promptAsyncAt(userId, target.getOpencodeSessionId(),
                    workspaceService.userDir(userId),
                    PromptAsyncBody.builder()
                            .parts(List.of(PromptAsyncBody.Part.builder().type("text").text(prompt).build()))
                            .build());
            processManager.touch(userId);
            markDelivered(tasksDir, id);
            log.info("injected background task {} result into session {} for user {}", id, target.getOpencodeSessionId(), userId);
        } catch (Exception e) {
            log.warn("inject background task {} result failed: {}", id, e.toString());
        }
    }

    /** 完成判定:优先 <id>.done 状态文件;其次 <id>.pid 进程已退出;均无则未完成。 */
    private String finishedStatus(Path tasksDir, String id, Path taskFile) {
        Path done = tasksDir.resolve(id + ".done");
        if (Files.exists(done)) {
            try {
                String s = Files.readString(done).trim();
                return s.isEmpty() ? "任务完成" : s;
            } catch (IOException e) {
                return "任务完成";
            }
        }
        Path pidFile = tasksDir.resolve(id + ".pid");
        if (Files.exists(pidFile)) {
            try {
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                return alive ? null : "任务完成(进程已退出)";
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 目标会话:优先 json.sessionId(须归属该用户),否则取最近活跃会话。 */
    private ChatSession resolveSession(Long userId, JsonNode spec) {
        String hint = spec.path("sessionId").asText("");
        if (!hint.isBlank()) {
            ChatSession s = chatSessionService.findByOpencodeSessionId(hint);
            if (s != null && userId.equals(s.getUserId())) {
                return s;
            }
        }
        return chatSessionService.findMostRecentByUserId(userId);
    }

    /** 读取文件尾部(UTF-8),最多 maxChars 字符;防止超大日志拖垮注入。 */
    private String tail(Path p, int maxChars) {
        if (!Files.exists(p)) {
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

    private void markDelivered(Path tasksDir, String id) {
        try {
            Files.writeString(tasksDir.resolve(id + ".delivered"), String.valueOf(System.currentTimeMillis()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.debug("mark delivered {} failed: {}", id, e.toString());
        }
    }

    private Long parseUserId(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stripSuffix(String name) {
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
