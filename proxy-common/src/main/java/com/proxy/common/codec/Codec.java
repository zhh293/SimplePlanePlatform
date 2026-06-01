package com.proxy.common.codec;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.SPI;

/**
 * 编解码器 SPI 接口 —— 帧级编解码
 * <p>
 * 负责将 ProxyMessage 编码为字节数组（用于网络传输），
 * 以及将字节数组解码为 ProxyMessage。
 * </p>
 */
@SPI("proxy")
public interface Codec {

    /**
     * 编码：将 ProxyMessage 序列化为字节
     *
     * @param message 代理消息
     * @return 编码后的字节数组
     * @throws CodecException 编码失败时抛出
     */
    byte[] encode(ProxyMessage message) throws CodecException;

    /**
     * 解码：将字节反序列化为 ProxyMessage
     *
     * @param data 字节数组
     * @return 解码后的代理消息
     * @throws CodecException 解码失败时抛出
     */
    ProxyMessage decode(byte[] data) throws CodecException;
}
