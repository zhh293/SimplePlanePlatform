package com.ladder.client.protocol;

public class LadderMessage {
    // 魔数，用于标识协议
    public static final byte[] MAGIC = new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE };
    // 版本号
    private byte version = 1;
    // 消息类型：0-HTTP请求(旧)，1-HTTP响应(旧)，2-建立连接(CONNECT)，3-数据传输(DATA)，4-断开连接(DISCONNECT)
    public static final byte TYPE_HTTP_REQUEST = 0;
    public static final byte TYPE_HTTP_RESPONSE = 1;
    public static final byte TYPE_CONNECT = 2;
    public static final byte TYPE_DATA = 3;
    public static final byte TYPE_DISCONNECT = 4;

    private byte type;
    // 请求ID
    private long requestId;
    // 数据长度
    private int dataLength;
    // 加密后的数据
    private byte[] encryptedData;

    public LadderMessage() {
    }

    public LadderMessage(byte type, long requestId, byte[] encryptedData) {
        this.type = type;
        this.requestId = requestId;
        this.encryptedData = encryptedData;
        this.dataLength = encryptedData != null ? encryptedData.length : 0;
    }

    public byte getVersion() {
        return version;
    }

    public void setVersion(byte version) {
        this.version = version;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public int getDataLength() {
        return dataLength;
    }

    public void setDataLength(int dataLength) {
        this.dataLength = dataLength;
    }

    public byte[] getEncryptedData() {
        return encryptedData;
    }

    public void setEncryptedData(byte[] encryptedData) {
        this.encryptedData = encryptedData;
        this.dataLength = encryptedData != null ? encryptedData.length : 0;
    }

    @Override
    public String toString() {
        return "LadderMessage{" +
                "version=" + version +
                ", type=" + type +
                ", requestId=" + requestId +
                ", dataLength=" + dataLength +
                ", encryptedData.length=" + (encryptedData != null ? encryptedData.length : 0) +
                '}';
    }
}