package com.proxy.common.transport;

import com.proxy.common.filter.Invoker;

/**
 * 标记接口 —— 表明 MessageHandler 实现持有 Invoker 引用
 * <p>
 * 由于"内聚一层"方案中 ServerChannelHandler 需要直接持有 Invoker，
 * 而 {@link Transporter#bind(com.proxy.common.model.URL, MessageHandler)} 签名只接受 MessageHandler，
 * 通过此接口让 Transporter 实现类能从 MessageHandler 中提取 Invoker。
 * </p>
 */
public interface InvokerAware {

    /**
     * 获取内部持有的 Invoker 实例
     *
     * @return Invoker
     */
    Invoker getInvoker();
}
