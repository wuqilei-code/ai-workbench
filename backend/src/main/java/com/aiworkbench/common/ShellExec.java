package com.aiworkbench.common;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 简单 shell 命令执行器(容器内 root 调用 useradd/chmod/chown/cp/getent 等)。
 * 供 per-user provisioning / config writer / process manager 复用。
 */
@Slf4j
public final class ShellExec {

    private ShellExec() {}

    /** 执行命令,返回 exit code;非 0 仅 warn(幂等场景下重复操作属预期)。 */
    public static int run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                log.warn("cmd '{}' exit={} out={}", String.join(" ", cmd), code, out.trim());
            }
            return code;
        } catch (IOException e) {
            log.error("cmd '{}' io error: {}", String.join(" ", cmd), e.toString());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("cmd '{}' interrupted", String.join(" ", cmd));
            return -1;
        }
    }

    /** getent &lt;db&gt; &lt;name&gt;:exit 0=存在 */
    public static boolean exists(String db, String name) {
        return run("getent", db, name) == 0;
    }
}
