package org.example;

/**
 * 协议转换管理器类，用于管理协议转换的相关操作
 * 采用单例模式设计，确保全局只有一个实例
 */
public class ProtocolTranslationManager {
    // 单例模式的实例，使用private static final确保全局唯一且不可变
    private static final ProtocolTranslationManager instance = new ProtocolTranslationManager();

    // 私有构造函数，防止外部实例化
    private ProtocolTranslationManager() {}

    /**
     * 获取单例实例的方法
     * @return 返回ProtocolTranslationManager的唯一实例
     */
    public static ProtocolTranslationManager getInstance() {
        return instance;
    }

    /**
     * 协议转换方法，将十六进制数据转换为设备协议格式
     * @param hexData 输入的十六进制数据字符串
     * @return 转换后的协议数据字符串
     */
    public String translate(String hexData) {
        return DeviceProtocolTranslator.translateFullPacket(hexData);
    }
}
