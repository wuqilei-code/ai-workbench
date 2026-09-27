package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.job.JobTaskRequest;
import com.aiworkbench.dto.job.JobTaskRunVo;
import com.aiworkbench.dto.job.JobTaskVo;
import com.aiworkbench.entity.JobTask;
import com.aiworkbench.entity.JobTaskRun;
import com.aiworkbench.service.WorkspaceService;
import com.aiworkbench.service.job.JobTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 定时任务接口:CRUD + 手动触发 + 取消 + 运行日志。 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobTaskService jobTaskService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public R<List<JobTaskVo>> list(@RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) Integer offset) {
        return R.ok(jobTaskService.toVo(jobTaskService.listByUser(SecurityUtil.currentUserId(), limit, offset)));
    }

    @PostMapping
    public R<JobTaskVo> create(@RequestBody JobTaskRequest req) {
        Long userId = SecurityUtil.currentUserId();
        JobTask job = jobTaskService.create(userId, req.getName(), req.getPrompt(), req.getCron(),
                req.getAgent(), Boolean.TRUE.equals(req.getAutoApprove()), req.getVariables());
        return R.ok(jobTaskService.toVo(job));
    }

    @PatchMapping("/{id}")
    public R<JobTaskVo> update(@PathVariable Long id, @RequestBody JobTaskRequest req) {
        jobTaskService.update(SecurityUtil.currentUserId(), id, req.getName(), req.getPrompt(), req.getCron(),
                req.getAgent(), req.getAutoApprove(), req.getEnabled(), req.getVariables());
        return R.ok(jobTaskService.toVo(jobTaskService.getOwned(SecurityUtil.currentUserId(), id)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        jobTaskService.delete(SecurityUtil.currentUserId(), id);
        return R.ok();
    }

    /** 手动立即执行(入队)。 */
    @PostMapping("/{id}/run")
    public R<Boolean> run(@PathVariable Long id) {
        return R.ok(jobTaskService.trigger(SecurityUtil.currentUserId(), id));
    }

    /** 取消排队/等待中的任务。 */
    @PostMapping("/{id}/cancel")
    public R<Boolean> cancel(@PathVariable Long id) {
        return R.ok(jobTaskService.cancel(SecurityUtil.currentUserId(), id));
    }

    /** 最近一次运行的日志尾部(取最新一次运行的 run.log)。 */
    @GetMapping("/{id}/log")
    public R<String> log(@PathVariable Long id, @RequestParam(required = false) Integer tail) {
        JobTask job = jobTaskService.getOwned(SecurityUtil.currentUserId(), id);
        List<JobTaskRun> runs = jobTaskService.listRuns(SecurityUtil.currentUserId(), id);
        if (!runs.isEmpty()) {
            JobTaskRun latest = runs.get(0);
            if (latest.getRunLogPath() != null) {
                try {
                    String content = workspaceService.readFile(job.getUserId(),
                            "jobs/" + job.getId() + "/runs/" + latest.getId() + "/run.log");
                    int n = (tail == null || tail < 0) ? 12_000 : Math.min(tail, 500_000);
                    if (content.length() > n) {
                        content = content.substring(content.length() - n);
                    }
                    return R.ok(content);
                } catch (Exception ignored) {
                    // fallthrough: 读最新 run 日志失败时回退到 lastResult
                }
            }
        }
        return R.ok(job.getLastResult() == null ? "" : job.getLastResult());
    }

    /** 全部任务的最近运行历史(执行历史页)。 */
    @GetMapping("/runs")
    public R<java.util.Map<String, Object>> allRuns(@RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) Integer offset) {
        Long userId = SecurityUtil.currentUserId();
        return R.ok(jobTaskService.listAllRuns(userId, limit == null ? 20 : limit, offset == null ? 0 : offset));
    }

    /** 某任务的全部运行历史(结果),新→旧。 */
    @GetMapping("/{id}/runs")
    public R<List<JobTaskRunVo>> runs(@PathVariable Long id) {
        Long userId = SecurityUtil.currentUserId();
        return R.ok(jobTaskService.toRunVo(jobTaskService.listRuns(userId, id)));
    }

    /** 某次运行的日志尾部。 */
    @GetMapping("/{id}/runs/{runId}/log")
    public R<String> runLog(@PathVariable Long id, @PathVariable Long runId,
                            @RequestParam(required = false) Integer tail) {
        Long userId = SecurityUtil.currentUserId();
        JobTask job = jobTaskService.getOwned(userId, id);
        JobTaskRun run = jobTaskService.listRuns(userId, id).stream()
                .filter(r -> r.getId().equals(runId)).findFirst()
                .orElseThrow(() -> new com.aiworkbench.common.BusinessException(
                        com.aiworkbench.common.ResultCode.NOT_FOUND));
        String content;
        try {
            content = workspaceService.readFile(job.getUserId(),
                    "jobs/" + job.getId() + "/runs/" + run.getId() + "/run.log");
        } catch (Exception e) {
            content = run.getResult() == null ? "(日志不可用)" : run.getResult();
        }
        int n = (tail == null || tail < 0) ? 200_000 : Math.min(tail, 1_000_000);
        boolean truncated = content.length() > n;
        if (truncated) {
            // 从末尾截断,但前推到下一个换行,保证首行完整(否则会看到"半行"日志)
            int start = content.length() - n;
            int nl = content.indexOf('\n', start);
            if (nl >= 0) start = nl + 1;
            content = content.substring(start);
            content = "[…日志较长,仅显示末尾部分…]\n" + content;
        }
        return R.ok(content);
    }
}