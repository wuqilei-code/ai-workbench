package com.aiworkbench.config;

import com.aiworkbench.controller.BrowserWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final BrowserWebSocketHandler browserWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(browserWebSocketHandler, "/ws/browser")
                .setAllowedOrigins("*");
    }

    /**
     * 调大 WebSocket 消息缓冲上限:截图 JPEG 帧可达数百 KB,默认 8KB 会静默发送失败(白屏)。
     * 同时放宽会话空闲超时,避免浏览器面板长时间挂着被断开。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(8 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(24 * 60 * 60 * 1000L);
        return container;
    }
}
