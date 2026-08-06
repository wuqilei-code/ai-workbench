package com.aiworkbench.dto.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文件写入请求(FileController.write)。
 * 取代原先的 Map<String,String>,提供类型安全的 Bean Validation。
 */
@Data
public class FileWriteRequest {
    /** 相对用户工作目录的路径,禁止包含 .. (WorkspaceService 会再次校验) */
    @NotBlank(message = "path 不能为空")
    @Size(max = 512, message = "path 过长")
    private String path;

    /** 文件内容;允许空串(清空文件) */
    @Size(max = 2_000_000, message = "文件内容过大(上限 2MB)")
    private String content;
}
