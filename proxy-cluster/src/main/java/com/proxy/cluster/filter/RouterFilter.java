package com.proxy.cluster.filter;

import com.proxy.common.filter.*;
import com.proxy.common.spi.Activate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 路由过滤器 —— 判断目标地址是走代理还是直连
 * <p>
 * 根据配置的直连规则（通配符匹配），决定请求是否需要经过远程代理。
 * 匹配直连规则的请求直接返回特殊标记，由上层处理本地直连。
 * 不匹配的请求继续走 Filter 链到远程代理。
 * </p>
 */
@Activate(group = "client", order = 100)
public class RouterFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RouterFilter.class);

    /**
     * 默认直连规则：本地地址不走代理
     */
    private final List<String> directRules = Arrays.asList(
            "localhost",
            "127.0.0.1",
            "*.local",
            "192.168.*",
            "10.*",
            "172.16.*",
            "172.17.*",
            "172.18.*",
            "172.19.*",
            "172.2?.*",
            "172.30.*",
            "172.31.*"
    );

    @Override
    public CompletableFuture<Response> invoke(Invoker invoker, Invocation invocation) throws ProxyException {
        String host = invocation.getTargetHost();

        if (shouldDirect(host)) {
            // 直连：标记路由决策，由上层处理
            invocation.setAttachment("route", "direct");
            log.debug("Route decision: DIRECT for {}", host);
            // 直连请求返回特殊响应，上层根据 attachment 判断
            Response response = Response.ok();
            response.getAttachments().put("route", "direct");
            return CompletableFuture.completedFuture(response);
        }

        // 走代理：继续 Filter 链
        invocation.setAttachment("route", "proxy");
        return invoker.invoke(invocation);
    }

    private boolean shouldDirect(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String rule : directRules) {
            if (matchWildcard(rule, host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 简单通配符匹配：支持 * 和 ?
     */
    private boolean matchWildcard(String pattern, String text) {
        // 转换为正则
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return text.matches(regex);
    }
}
