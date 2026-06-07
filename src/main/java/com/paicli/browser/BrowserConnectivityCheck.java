package com.paicli.browser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Duration;
/**
 * 浏览器连通性检查
 */
public class BrowserConnectivityCheck {
    /**
     * OkHttpClient 实例，用于发送 HTTP 请求
     */
    private final OkHttpClient client;

    /**
     * 构造函数，初始化 OkHttpClient 实例
     */
    public BrowserConnectivityCheck() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(2))
                .build());
    }

    /**
     * 构造函数，初始化 OkHttpClient 实例
     *
     * @param client OkHttpClient 实例
     */
    BrowserConnectivityCheck(OkHttpClient client) {
        this.client = client;
    }

    /**
     * 检查浏览器连通性
     *
     * @param port 浏览器端口
     * @return ProbeResult 连通性检查结果
     */
    public ProbeResult probe(int port) {
        if (port < 1024 || port > 65535) {
            return ProbeResult.failed("端口必须在 1024-65535 之间");
        }
        String url = "http://127.0.0.1:" + port + "/json/version";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ProbeResult.failed("HTTP " + response.code());
            }
            return ProbeResult.ok("http://127.0.0.1:" + port);
        } catch (Exception e) {
            return ProbeResult.failed(e.getMessage());
        }
    }

    /**
     * 连通性检查结果
     */
    public record ProbeResult(boolean ok, String browserUrl, String message) {
        /**
         * 创建一个表示成功的 ProbeResult
         *
         * @param browserUrl 浏览器地址
         * @return ProbeResult 连通性检查结果
         */
        static ProbeResult ok(String browserUrl) {
            return new ProbeResult(true, browserUrl, "ok");
        }

        /**
         * 创建一个表示失败的 ProbeResult
         *
         * @param message 失败信息
         * @return ProbeResult 连通性检查结果
         */
        static ProbeResult failed(String message) {
            return new ProbeResult(false, null, message == null ? "连接失败" : message);
        }
    }
}
