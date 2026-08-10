package com.aiworkbench.controller;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.aiworkbench.service.opencode.process.UserProcess;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Agent 服务预览透传代理。
 * <p>
 * 用户浏览器请求 {@code /api/preview/{port}/...},本控制器把请求原样转发到该用户 agent 容器
 * (容器模式:容器名 DNS {@code aiwb-agent-<uid>:<port>};进程模式:本机 {@code 127.0.0.1:<port>})
 * 上由 agent 启动的任意 HTTP 服务,并把响应(含 SSE/字节流)流式回给浏览器。
 * 鉴权沿用 /api/** 的 JWT 过滤器,天然保证用户只能访问自己 agent 容器内的服务。
 */
@Slf4j
@RestController
@RequestMapping("/api/preview/{port}")
public class PreviewController {

    /** 请求端口上限(65535) */
    private static final int MAX_PORT = 65535;

    private final OpencodeProperties props;
    private final OpencodeProcessManager processManager;

    public PreviewController(OpencodeProperties props, OpencodeProcessManager processManager) {
        this.props = props;
        this.processManager = processManager;
    }

    @RequestMapping({"", "/**"})
    public ResponseEntity<StreamingResponseBody> preview(
            @PathVariable int port,
            HttpServletRequest request) {
        if (port <= 0 || port > MAX_PORT) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "非法预览端口: " + port);
        }
        Long userId = SecurityUtil.currentUserId();
        // 确保该用户 agent 进程/容器存在(预览是主动访问,若已被 idle 回收则先拉起)。
        UserProcess up = processManager.getOrStart(userId);

        String baseUrl = props.isContainerMode()
                ? "http://aiwb-agent-" + up.getUid() + ":" + port
                : "http://127.0.0.1:" + port;

        // 去掉 /api/preview/{port} 前缀,保留剩余路径(如 /api/preview/8000 → /; /api/preview/8000/a/b → /a/b)
        String uri = request.getRequestURI();
        String prefix = "/api/preview/" + port;
        if (uri.startsWith(prefix)) {
            uri = uri.substring(prefix.length());
        }
        if (uri.isEmpty()) {
            uri = "/";
        }
        if (request.getQueryString() != null) {
            uri += "?" + stripAuthParams(request.getQueryString());
        }

        WebClient client = WebClient.builder().baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        WebClient.RequestBodySpec spec = client.method(requireHttpMethod(request.getMethod())).uri(uri);
        copyHeaders(request, spec);
        byte[] body = readBody(request);
        if (body.length > 0) {
            // 注意:byte[] 走 fromValue 会被 WebClient 按 JSON(base64)编码,而非原始字节;
            // 表单/x-www-form-urlencoded 与任意二进制 body 必须原样转发,故用 DataBuffer。
            org.springframework.core.io.buffer.DataBuffer buf =
                    org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(body);
            spec.body(BodyInserters.fromDataBuffers(reactor.core.publisher.Flux.just(buf)));
        }

        return forward(spec, request, port, resolveToken(request));
    }

    private HttpMethod requireHttpMethod(String method) {
        try {
            return HttpMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不支持的请求方法: " + method);
        }
    }

    /** 透传请求头(Host/Accept-Encoding 等 hop-by-hop 头除外,交由目标服务器处理)。 */
    private void copyHeaders(HttpServletRequest request, WebClient.RequestBodySpec spec) {
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase();
            if (HOP_BY_HOP_HEADERS.contains(lower)) {
                continue;
            }
            // 鉴权凭据(Authorization/cookie 中的 aiwb_token)不透传给 agent 服务
            if ("authorization".equals(lower)) {
                continue;
            }
            // Content-Length 由 WebClient 按 body 自动计算;透传旧值会与 BodyInserters 不符导致请求挂起
            if ("content-length".equals(lower)) {
                continue;
            }
            if ("cookie".equals(lower)) {
                stripTokenCookie(request, spec);
                continue;
            }
            java.util.Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                spec.header(name, values.nextElement());
            }
        }
    }

    /** 透传 Cookie 头,但剔除 aiwb_token(网关鉴权凭据,与 agent 服务无关)。 */
    private void stripTokenCookie(HttpServletRequest request, WebClient.RequestBodySpec spec) {
        java.util.Enumeration<String> values = request.getHeaders("cookie");
        StringBuilder kept = new StringBuilder();
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            for (String part : value.split(";")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.toLowerCase().startsWith(com.aiworkbench.controller.AuthController.TOKEN_COOKIE + "=")) {
                    continue;
                }
                if (kept.length() > 0) {
                    kept.append("; ");
                }
                kept.append(trimmed);
            }
        }
        if (kept.length() > 0) {
            spec.header("Cookie", kept.toString());
        }
    }

    /** 剔除 query 中的鉴权参数 token(预览 URL 可能带 token=xxx)。 */
    private String stripAuthParams(String query) {
        StringBuilder kept = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            String name = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            if ("token".equals(name)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(part);
        }
        return kept.toString();
    }

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    private byte[] readBody(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] raw = in.readAllBytes();
            // Spring FormContentFilter 会消费 application/x-www-form-urlencoded 的 body,
            // 导致 getInputStream() 读到 0 字节。此时从 getParameterMap 重建原始 body。
            if (raw.length == 0 && request.getParameterMap() != null && !request.getParameterMap().isEmpty()) {
                String ctype = request.getContentType();
                if (ctype != null && ctype.toLowerCase().contains("x-www-form-urlencoded")) {
                    StringBuilder sb = new StringBuilder();
                    request.getParameterMap().forEach((k, vs) -> {
                        for (String v : vs) {
                            if (sb.length() > 0) {
                                sb.append('&');
                            }
                            sb.append(encode(k)).append('=').append(encode(v));
                        }
                    });
                    return sb.toString().getBytes(StandardCharsets.UTF_8);
                }
            }
            return raw;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "读取请求体失败: " + e.getMessage());
        }
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private ResponseEntity<StreamingResponseBody> forward(WebClient.RequestBodySpec spec, HttpServletRequest request, int port, String token) {
        // 阻塞取响应头(目标服务通常秒级返回;SSE 只阻塞到首个事件头)
        var response = spec.exchange().block();
        if (response == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "预览服务无响应");
        }
        org.springframework.http.HttpStatusCode status = response.statusCode();

        // 回传响应头(content-type / content-length 等),忽略 hop-by-hop
        HttpHeaders headers = new HttpHeaders();
        response.headers().asHttpHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, values);
            }
        });
        // content-length 由转发方流式写出,无法提前得知,移除以免截断
        headers.remove(HttpHeaders.CONTENT_LENGTH);

        // 重写 Location(302/301 跳转):Flask/FastAPI 的 url_for/redirect 生成根绝对路径,
        // 若不补预览前缀,浏览器会跳到根路径丢失 token → 网关 401。同时追加登录 token。
        List<String> locations = headers.get(HttpHeaders.LOCATION);
        if (locations != null && !locations.isEmpty()) {
            String rewritten = rewriteRedirectLocation(locations.get(0), port, token);
            headers.set(HttpHeaders.LOCATION, rewritten);
        }

        // HTML 响应:应用通常用 url_for/相对根路径生成 /static/... 等绝对路径,
        // 直接透传会在浏览器请求根路径而丢失预览前缀。整页缓冲后重写绝对路径。
        String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            byte[] html = response.bodyToMono(byte[].class).block();
            if (html == null) {
                html = new byte[0];
            }
            byte[] rewritten = rewriteHtmlPaths(html, port, token);
            headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(rewritten.length));
            byte[] out = rewritten;
            return ResponseEntity.status(status).headers(headers)
                    .body(outStream -> outStream.write(out));
        }

        StreamingResponseBody body = out -> streamBody(response, out);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    /** 重写 HTML 里的根绝对路径:在 /xxx(/login、/static/a.css 等)前补预览前缀,
     *  使其在 /api/preview/{port} 子路径下正确解析。跳过 //、已带前缀、片段锚点等。
     *  <p>对导航链接(href/action)追加当前登录 token:站内跳转是顶层导航,不带 Authorization 头,
     *  且 cookie 跨域/未重新登录时不保证存在,故显式注入 URL 参数保证鉴权。静态资源(src/url)不追加,
     *  由页面同源的 cookie 提供(或资源本身无需鉴权)。 */
    private byte[] rewriteHtmlPaths(byte[] html, int port, String token) {
        String text = new String(html, StandardCharsets.UTF_8);
        String prefix = "/api/preview/" + port;
        String tokenSuffix = (token == null || token.isBlank()) ? "" : token;
        // 1) 导航链接(href/action):重写前缀 + 追加 token
        String rewritten = text.replaceAll(
                "(?i)(\\b(?:href|action|data-href)\\s*=\\s*[\"'])(/)(?!\\/|api/preview/|api/|#)",
                "$1" + prefix + "$2");
        if (!tokenSuffix.isEmpty()) {
            rewritten = appendTokenToLinks(rewritten, prefix, tokenSuffix);
        }
        // 2) 静态资源(src/poster/data-src)与 CSS url(...):只重写前缀,不追加 token(cookie 鉴权)
        rewritten = rewritten.replaceAll(
                "(?i)(\\b(?:src|poster|data-src)\\s*=\\s*[\"'])(/)(?!\\/|api/preview/|api/|#)",
                "$1" + prefix + "$2");
        rewritten = rewritten.replaceAll(
                "(?i)(url\\([\"']?)(/)(?!\\/|api/preview/|api/|#)",
                "$1" + prefix + "$2");
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    /** 重写 302/301 的 Location:根绝对路径补预览前缀 + 登录 token。 */
    private String rewriteRedirectLocation(String location, int port, String token) {
        String prefix = "/api/preview/" + port;
        // 已是预览前缀 → 仅补 token
        if (location.startsWith(prefix)) {
            return appendTokenToUrl(location, token);
        }
        // 根绝对路径(以 / 开头,非 //、非 /api/、非 /api/preview/、非 #)
        if (location.startsWith("/") && !location.startsWith("//")
                && !location.startsWith("/api/") && !location.startsWith("#")) {
            return appendTokenToUrl(prefix + location, token);
        }
        // 完整 URL 或相对路径:保持原样(完整 URL 由浏览器同源处理,相对路径本身就在预览上下文内)
        return location;
    }

    /** 给 URL 追加 token 参数(已带则跳过)。 */
    private String appendTokenToUrl(String url, String token) {
        if (token == null || token.isBlank()) {
            return url;
        }
        if (url.contains("token=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "token=" + token;
    }

    /** 给 HTML 中已重写为 /api/preview/{port}/... 的 href/action 值追加 ?token=...(仅当该值不带 token)。 */
    private String appendTokenToLinks(String html, String prefix, String token) {
        // 匹配 <a href="..."> 或 <form action="..."> 内的 href/action 值(已带 prefix)
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)(\\b(?:href|action)\\s*=\\s*[\"'])(" + java.util.regex.Pattern.quote(prefix) + "[^\"']*)([\"'])");
        java.util.regex.Matcher m = p.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String url = m.group(2);
            String withToken;
            if (url.contains("token=")) {
                withToken = url;
            } else {
                withToken = url + (url.contains("?") ? "&" : "?") + "token=" + token;
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + withToken + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 从当前请求提取登录 JWT:header Authorization → cookie aiwb_token → query token。 */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (request.getParameter("token") != null && !request.getParameter("token").isBlank()) {
            return request.getParameter("token");
        }
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie c : cookies) {
                if (com.aiworkbench.controller.AuthController.TOKEN_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** 把上游响应 body 逐块流式写入浏览器输出流。 */
    private void streamBody(
            org.springframework.web.reactive.function.client.ClientResponse response,
            OutputStream out) throws IOException {
        try {
            response.bodyToFlux(DataBuffer.class).doOnNext(db -> {
                try {
                    try (InputStream in = db.asInputStream()) {
                        in.transferTo(out);
                    }
                    out.flush();
                } catch (IOException e) {
                    log.warn("preview: write to client failed: {}", e.getMessage());
                } finally {
                    DataBufferUtils.release(db);
                }
            }).blockLast();
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // 客户端可能已断开
            }
        }
    }
}
