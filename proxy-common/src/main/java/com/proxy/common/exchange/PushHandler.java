package com.proxy.common.exchange;

import com.proxy.common.model.ProxyMessage;

/**
 * 服务端推送处理器（数据面回调出口）。
 * <p>
 * 在“一问一答”的请求-响应模型之外，代理隧道的<strong>数据面</strong>采用
 * <em>流式推送</em>模型：客户端通过 streamId 把上行数据“发后即忘”地送出，
 * 目标站点的回包由远程服务器主动<strong>推送</strong>（requestId=0 + streamId）。
 * </p>
 * <p>
 * 本接口即推送数据在客户端的落地出口。{@link ExchangeClient#setPushHandler} 注册后，
 * IO 线程收到 requestId=0 的入站消息时回调此处，由上层按 streamId 路由
 * （例如写回对应的浏览器连接）。
 * </p>
 * <p>
 * 这正是“两套 ID 各归其位”的体现：requestId/Future 只服务控制面（CONNECT/DISCONNECT），
 * streamId 独占数据面寻址，数据流不再生成无意义的 Future。
 * </p>
 */
@FunctionalInterface
public interface PushHandler {

    /**
     * 收到一条服务端推送消息（requestId=0）。
     *
     * @param message 推送消息，携带 streamId、data、type（DATA/DISCONNECT）
     */
    void onPush(ProxyMessage message);
}
