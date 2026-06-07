package com.paicli.browser;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前 PaiCLI 浏览器会话状态。
 *
 * 由 Main 持有并注入 ToolRegistry，避免做全局单例污染测试与多会话运行。
 */
public class BrowserSession {
    // 浏览器模式：隔离模式或共享模式
    private BrowserMode mode = BrowserMode.ISOLATED;
    // 浏览器 URL
    private String browserUrl;
    // Agent最后导航的 URL
    private String lastNavigatedUrl;
    // Agent打开的标签页（使用LinkedHashSet保持插入顺序）
    private final Set<String> agentOpenedTabs = new LinkedHashSet<>();

    public synchronized BrowserMode mode() {
        return mode;
    }

    public synchronized String browserUrl() {
        return browserUrl;
    }

    public synchronized String lastNavigatedUrl() {
        return lastNavigatedUrl;
    }

    public synchronized void switchToIsolated() {
        mode = BrowserMode.ISOLATED;
        browserUrl = null;
        lastNavigatedUrl = null;
        agentOpenedTabs.clear();
    }

    public synchronized void switchToShared(String browserUrl) {
        mode = BrowserMode.SHARED;
        this.browserUrl = browserUrl;
        lastNavigatedUrl = null;
        agentOpenedTabs.clear();
    }

    public synchronized void rememberNavigation(String url) {
        if (url != null && !url.isBlank()) {
            lastNavigatedUrl = url;
        }
    }

    public synchronized void recordOpenedTab(String pageId) {
        if (pageId != null && !pageId.isBlank()) {
            agentOpenedTabs.add(pageId);
        }
    }

    public synchronized boolean isAgentOpenedTab(String pageId) {
        return pageId != null && agentOpenedTabs.contains(pageId);
    }

    public synchronized Set<String> agentOpenedTabs() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(agentOpenedTabs));
    }

    public synchronized void clearAgentOpenedTabs() {
        agentOpenedTabs.clear();
    }
}
