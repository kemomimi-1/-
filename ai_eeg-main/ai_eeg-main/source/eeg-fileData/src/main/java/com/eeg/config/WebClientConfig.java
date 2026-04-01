//  WebClient配置类
package com.eeg.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient配置类 - 为AI模型服务和其他HTTP调用提供优化的WebClient
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final AIModelConfig aiConfig;

    /**
     * 配置用于AI模型调用的WebClient
     */
    @Bean
    public WebClient aiModelWebClient() {
        // 配置HTTP客户端超时和连接池
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, aiConfig.getTimeout() * 1000)
                .responseTimeout(Duration.ofSeconds(aiConfig.getTimeout()))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(aiConfig.getTimeout(), TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(aiConfig.getTimeout(), TimeUnit.SECONDS))
                );

        // 配置内存缓冲区大小（处理大的AI响应）
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * 通用WebClient Bean
     */
    @Bean
    public WebClient generalWebClient() {
        return WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build())
                .build();
    }
}