package org.example;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 串口核心管理器 (单例模式)
 *
 * 【页面隔离机制】
 *   1. 页面注册监听器带 pageTag：addListener("ParametersView", listener)
 *   2. 页面发送数据带 pageTag：sendData(bytes, "ParametersView")
 *   3. 内部记录"当前活跃 pageTag"，回包只发给 tag 匹配的监听器
 *   4. pageTag 只是 Java 内部字符串标记，不写入串口数据
 *   5. 超过 SENDER_TAG_EXPIRE_MS 未发送新数据，tag 失效，回包视为无归属广播
 *   6. 无 tag 的监听器（旧代码）依然收到所有数据
 *
 * 【设备地址机制】★ 新增
 *   1. 通过 setDeviceAddress(十进制) 设置全局设备地址（0~255）
 *   2. 所有 sendData 在发送前自动把首字节替换为该地址
 *   3. 替换发生在算 CRC 之前，CRC 依然正确
 *   4. 未设置地址时（null），保留原报文的第一个字节
 *   5. 所有页面无需改动即可生效
 */
public class SerialManager {

    // ==========================================================
    //  单例
    // ==========================================================
    private static SerialManager instance;
    private SerialManager() {}

    public static SerialManager getInstance() {
        if (instance == null) {
            synchronized (SerialManager.class) {
                if (instance == null) {
                    instance = new SerialManager();
                }
            }
        }
        return instance;
    }

    // ==========================================================
    //  底层字段
    // ==========================================================
    private SerialPort comPort;
    private OutputStream outputStream;
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private Timer readTimer;
    private Timer modbusFrameTimer;
    private boolean isCurrentCrcEnabled = true;

    private final ProtocolTranslationManager translationManager = ProtocolTranslationManager.getInstance();

    // ==========================================================
    //  页面隔离：活跃发送者 tag
    // ==========================================================
    private final AtomicReference<String> activeSenderTag = new AtomicReference<>(null);
    private volatile long activeSenderTimestamp = 0L;
    private static final long SENDER_TAG_EXPIRE_MS = 3000L;

    // ==========================================================
    //  ★ 设备地址（全局，十进制 0~255）
    // ==========================================================
    /** 当前设备地址(十进制)；null 表示不替换，保留原首字节 */
    private final AtomicReference<Integer> deviceAddress = new AtomicReference<>(null);

    /**
     * 设置设备地址（十进制 0~255）。
     * 传 null 表示禁用替换。
     */
    public void setDeviceAddress(Integer addr) {
        if (addr == null) {
            deviceAddress.set(null);
            return;
        }
        if (addr < 0 || addr > 255) {
            throw new IllegalArgumentException("设备地址超出范围(0~255): " + addr);
        }
        deviceAddress.set(addr);
    }

    /**
     * 从字符串设置设备地址，允许 "1" / " 10 " / "255"。
     * 空串或 null 表示禁用替换。
     */
    public void setDeviceAddress(String addrText) {
        if (addrText == null || addrText.trim().isEmpty()) {
            deviceAddress.set(null);
            return;
        }
        try {
            int v = Integer.parseInt(addrText.trim());
            setDeviceAddress(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("设备地址必须是十进制数字(0~255): " + addrText);
        }
    }

    public Integer getDeviceAddress() {
        return deviceAddress.get();
    }

    public boolean hasDeviceAddress() {
        return deviceAddress.get() != null;
    }

    /** 用当前设备地址替换首字节；未设置则原样返回 */
    private byte[] applyDeviceAddress(byte[] data) {
        Integer addr = deviceAddress.get();
        if (addr == null || data == null || data.length == 0) return data;

        byte newAddr = (byte) (addr & 0xFF);
        if (data[0] == newAddr) return data;   // 已经一致，不重复分配

        byte[] copy = data.clone();
        copy[0] = newAddr;
        return copy;
    }

    // ==========================================================
    //  监听器容器
    // ==========================================================
    private final List<TaggedListener> listeners = new CopyOnWriteArrayList<>();

    public interface SerialEventListener {
        void onRawData(String rawHex);
        void onTranslatedData(String translatedText);
        void onSystemLog(String log);
        void onError(String error);
    }

    private static class TaggedListener {
        final String tag;                    // null 表示全局监听
        final SerialEventListener listener;
        TaggedListener(String tag, SerialEventListener listener) {
            this.tag = tag;
            this.listener = listener;
        }
    }

    // ==========================================================
    //  注册 / 移除监听器
    // ==========================================================

    public void addListener(String pageTag, SerialEventListener listener) {
        if (listener == null) return;
        listeners.add(new TaggedListener(pageTag, listener));
    }

    @Deprecated
    public void addListener(SerialEventListener listener) {
        addListener(null, listener);
    }

    public void removeListener(SerialEventListener listener) {
        if (listener == null) return;
        listeners.removeIf(t -> t.listener == listener);
    }

    public void removeListenersByTag(String pageTag) {
        if (pageTag == null) return;
        listeners.removeIf(t -> pageTag.equals(t.tag));
    }

    // ==========================================================
    //  发送数据
    // ==========================================================

    public void sendData(byte[] data, String pageTag) throws IOException {
        activeSenderTag.set(pageTag);
        activeSenderTimestamp = System.currentTimeMillis();
        doSend(data);
    }

    @Deprecated
    public void sendData(byte[] data) throws IOException {
        activeSenderTag.set(null);
        activeSenderTimestamp = 0L;
        doSend(data);
    }

    /** 实际发送逻辑（协议原样加 CRC；设备地址替换最先执行） */
    private void doSend(byte[] data) throws IOException {
        if (outputStream == null) return;

        // ★ 统一替换首字节设备地址（必须在算 CRC 之前）
        data = applyDeviceAddress(data);

        byte[] finalDataToSend = data;
        if (isCurrentCrcEnabled) {
            int crc = Crc16Util.calculateCRC16(data, 0, data.length);
            byte[] dataWithCrc = new byte[data.length + 2];
            System.arraycopy(data, 0, dataWithCrc, 0, data.length);
            dataWithCrc[dataWithCrc.length - 2] = (byte) (crc & 0xFF);
            dataWithCrc[dataWithCrc.length - 1] = (byte) ((crc >> 8) & 0xFF);
            finalDataToSend = dataWithCrc;
        }

        StringBuilder hexBuilder = new StringBuilder();
        for (byte b : finalDataToSend) hexBuilder.append(String.format("%02X ", b));
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
        broadcastRawData("[" + timestamp + " 发送]: " + hexBuilder.toString().trim());

        ModbusUtils.parseSendDataToUpdateAddress(data);
        outputStream.write(finalDataToSend);
        outputStream.flush();
    }

    public void setCrcEnabled(boolean crcEnabled) {
        this.isCurrentCrcEnabled = crcEnabled;
    }

    // ==========================================================
    //  广播分发
    // ==========================================================
    private String resolveActiveTag() {
        if (activeSenderTimestamp == 0) return null;
        if (System.currentTimeMillis() - activeSenderTimestamp > SENDER_TAG_EXPIRE_MS) {
            return null;
        }
        return activeSenderTag.get();
    }

    private void broadcastRawData(String text) {
        String active = resolveActiveTag();
        for (TaggedListener t : listeners) {
            if (t.tag == null || t.tag.equals(active)) {
                t.listener.onRawData(text);
            }
        }
    }

    private void broadcastTranslatedData(String text) {
        String active = resolveActiveTag();
        for (TaggedListener t : listeners) {
            if (t.tag == null || t.tag.equals(active)) {
                t.listener.onTranslatedData(text);
            }
        }
    }

    private void broadcastSystemLog(String log) {
        for (TaggedListener t : listeners) t.listener.onSystemLog(log);
    }

    private void broadcastError(String error) {
        for (TaggedListener t : listeners) t.listener.onError(error);
    }

    // ==========================================================
    //  串口开关
    // ==========================================================
    public String[] getAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] portNames = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            portNames[i] = ports[i].getSystemPortName();
        }
        return portNames;
    }

    public boolean openPort(String portName, int baudRate) {
        comPort = SerialPort.getCommPort(portName);
        comPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        comPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (comPort.openPort()) {
            try {
                outputStream = comPort.getOutputStream();
            } catch (Exception e) {
                e.printStackTrace();
            }
            startEventListening();
            startHighFreqPolling();
            broadcastSystemLog("串口 " + portName + " 已打开");
            return true;
        }
        broadcastError("串口打开失败");
        return false;
    }


    public boolean isPortOpen() {
        return comPort != null && comPort.isOpen();
    }

    public void closePort() {
        // 停止所有定时器
        if (readTimer != null) {
            readTimer.cancel();
            readTimer = null;
        }
        if (modbusFrameTimer != null) {
            modbusFrameTimer.cancel();
            modbusFrameTimer = null;
        }

        // 关闭输出流
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        // 关闭串口
        if (comPort != null && comPort.isOpen()) {
            comPort.closePort();
            comPort = null;
        }

        // 清空缓冲区
        receiveBuffer.reset();

        // 清理监听器
        listeners.clear();

        broadcastSystemLog("串口已关闭，资源已释放");
    }


    // ==========================================================
    //  数据读取
    // ==========================================================
    private void startEventListening() {
        comPort.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() { return SerialPort.LISTENING_EVENT_DATA_AVAILABLE; }
            @Override
            public void serialEvent(SerialPortEvent event) { drainInputStream(); }
        });
    }

    private void startHighFreqPolling() {
        readTimer = new Timer("ReadTimer", true);
        readTimer.schedule(new TimerTask() {
            @Override
            public void run() { drainInputStream(); }
        }, 10, 20);
    }

    private void drainInputStream() {
        try {
            while (comPort != null && comPort.isOpen() && comPort.bytesAvailable() > 0) {
                byte[] buffer = new byte[comPort.bytesAvailable()];
                int numRead = comPort.readBytes(buffer, buffer.length);
                if (numRead > 0) {
                    receiveBuffer.write(buffer, 0, numRead);
                    StringBuilder hexBuilder = new StringBuilder();
                    for (byte b : buffer) hexBuilder.append(String.format("%02X ", b));
                    broadcastRawData(hexBuilder.toString().trim());
                }
            }

            if (receiveBuffer.size() > 0) {
                if (isCurrentCrcEnabled) {
                    tryParseModbusBuffer();
                } else {
                    byte[] dataSnapshot = receiveBuffer.toByteArray();
                    receiveBuffer.reset();
                    String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                    broadcastTranslatedData("[" + timestamp + " 接收(无CRC)]: "
                            + new String(dataSnapshot, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ==========================================================
    //  Modbus 拼包 / 解析
    // ==========================================================
    private void tryParseModbusBuffer() {
        byte[] currentData = receiveBuffer.toByteArray();
        if (currentData.length < 1) return;

        int expectedFrameLength = calculateExpectedFrameLength(currentData);

        if (expectedFrameLength > 0 && currentData.length < expectedFrameLength) {
            startModbusFrameTimeout();
            return;
        }

        int consumedBytes = parseModbusFrame(currentData);

        if (consumedBytes > 0) {
            byte[] remaining = new byte[currentData.length - consumedBytes];
            System.arraycopy(currentData, consumedBytes, remaining, 0, remaining.length);
            receiveBuffer.reset();
            receiveBuffer.write(remaining, 0, remaining.length);

            if (remaining.length > 0) {
                tryParseModbusBuffer();
            } else {
                if (modbusFrameTimer != null) modbusFrameTimer.cancel();
            }
        }
    }

    private int calculateExpectedFrameLength(byte[] data) {
        if (data.length < 2) return -1;
        int funcCode = data[1] & 0xFF;
        if (funcCode > 0x80) return 5;
        if (funcCode == 0x03) {
            if (data.length < 3) return -1;
            int byteCount = data[2] & 0xFF;
            return 3 + byteCount + 2;
        }
        return -1;
    }

    private void startModbusFrameTimeout() {
        if (modbusFrameTimer != null) modbusFrameTimer.cancel();
        modbusFrameTimer = new Timer();
        modbusFrameTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (receiveBuffer.size() > 0) {
                    byte[] snapshot = receiveBuffer.toByteArray();
                    receiveBuffer.reset();
                    broadcastError("接收数据超时，可能存在残帧或CRC校验错误");
                    String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                    StringBuilder hex = new StringBuilder("[" + timestamp + " 残帧]: ");
                    for (byte b : snapshot) hex.append(String.format("%02X ", b));
                    broadcastTranslatedData(hex.toString());
                }
            }
        }, 100);
    }

    private int parseModbusFrame(byte[] bufferData) {
        int i = 0;
        while (i < bufferData.length) {
            if (bufferData.length - i < 5) break;

            int functionCode = bufferData[i + 1] & 0xFF;
            int expectedFrameLength;

            if (functionCode > 0x80) {
                expectedFrameLength = 5;
            } else {
                if (bufferData.length - i < 3) break;
                int dataByteCount = bufferData[i + 2] & 0xFF;
                expectedFrameLength = 3 + dataByteCount + 2;
            }

            if (expectedFrameLength > 256 || expectedFrameLength < 5) {
                i++;
                continue;
            }

            if (bufferData.length - i >= expectedFrameLength) {
                byte[] completeFrame = new byte[expectedFrameLength];
                System.arraycopy(bufferData, i, completeFrame, 0, expectedFrameLength);

                if (Crc16Util.verifyCRC16(completeFrame, 0, expectedFrameLength)) {
                    String translated = ModbusUtils.parseModbusFrame(completeFrame);
                    if (translated != null && !translated.isEmpty()) {
                        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                        broadcastTranslatedData("[" + timestamp + " 接收]:\n" + translated);
                    }
                    i += expectedFrameLength;
                } else {
                    broadcastError("Modbus CRC校验失败");
                    i++;
                }
            } else {
                break;
            }
        }
        return i;
    }

    // ==========================================================
    //  通用工具
    // ==========================================================
    public boolean exportTextData(String content, String initialFileName, Window ownerWindow) {
        if (content == null || content.isEmpty()) return false;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出数据");
        fileChooser.setInitialFileName(initialFileName);
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("CSV文件", "*.csv"));
        File file = fileChooser.showSaveDialog(ownerWindow);
        if (file != null) {
            try {
                byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                fos.write(bom);
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.close();
                return true;
            } catch (IOException e) {
                broadcastError("导出失败: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    public String importTextData(Window ownerWindow) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导入数据");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg"));        File file = fileChooser.showOpenDialog(ownerWindow);
        if (file != null) {
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                broadcastError("导入失败: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public void clearTextAreas(TextArea... textAreas) {
        for (TextArea ta : textAreas) { if (ta != null) ta.clear(); }
    }
}