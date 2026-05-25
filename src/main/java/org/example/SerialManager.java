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

/**
 * 串口核心管理器 (单例模式)
 * 职责：底层通讯、数据拼包、协议翻译、事件广播、通用工具服务
 */
public class SerialManager {

    private static final SerialManager INSTANCE = new SerialManager();
    public static SerialManager getInstance() { return INSTANCE; }
    private SerialManager() {}

    private SerialPort comPort;
    private OutputStream outputStream;
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private Timer readTimer;
    private Timer modbusFrameTimer;
    private boolean isCurrentCrcEnabled = true;

    private final List<SerialEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(SerialEventListener listener) { listeners.add(listener); }
    public void removeListener(SerialEventListener listener) { listeners.remove(listener); }

    public interface SerialEventListener {
        void onRawData(String rawHex);
        void onTranslatedData(String translatedText);
        void onSystemLog(String log);
        void onError(String error);
    }

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

    public void closePort() {
        if (readTimer != null) { readTimer.cancel(); readTimer = null; }
        if (modbusFrameTimer != null) { modbusFrameTimer.cancel(); modbusFrameTimer = null; }

        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ex) { ex.printStackTrace(); }

        if (comPort != null && comPort.isOpen()) {
            comPort.closePort();
            broadcastSystemLog("串口已关闭");
        }
        comPort = null;
    }

    public boolean isPortOpen() {
        return comPort != null && comPort.isOpen();
    }

    public void sendData(byte[] data) throws IOException {
        if (outputStream != null) {
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
    }

    public void setCrcEnabled(boolean crcEnabled) {
        this.isCurrentCrcEnabled = crcEnabled;
    }

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
            // 1. 将底层串口数据持续读入 receiveBuffer
            while (comPort != null && comPort.isOpen() && comPort.bytesAvailable() > 0) {
                byte[] buffer = new byte[comPort.bytesAvailable()];
                int numRead = comPort.readBytes(buffer, buffer.length);
                if (numRead > 0) {
                    receiveBuffer.write(buffer, 0, numRead);
                    // 原始十六进制打印（只打印新收到的）
                    StringBuilder hexBuilder = new StringBuilder();
                    for (byte b : buffer) hexBuilder.append(String.format("%02X ", b));
                    broadcastRawData(hexBuilder.toString().trim());
                }
            }

            // 2. 尝试解析缓冲区里的数据
            if (receiveBuffer.size() > 0) {
                if (isCurrentCrcEnabled) {
                    tryParseModbusBuffer();
                } else {
                    byte[] dataSnapshot = receiveBuffer.toByteArray();
                    receiveBuffer.reset(); // 非CRC模式直接清空
                    String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                    broadcastTranslatedData("[" + timestamp + " 接收(无CRC)]: " + new String(dataSnapshot, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * 核心修复：尝试解析缓冲区，根据解析结果消费有效数据
     */
    private void tryParseModbusBuffer() {
        byte[] currentData = receiveBuffer.toByteArray();
        if (currentData.length < 1) return;

        // 计算期望的最小帧长度
        int expectedFrameLength = calculateExpectedFrameLength(currentData);

        // 情况1：数据还不够一个完整的帧长度，什么都不做，等待下次串口数据到来
        if (expectedFrameLength > 0 && currentData.length < expectedFrameLength) {
            startModbusFrameTimeout(); // 开启超时保护
            return;
        }

        // 情况2：数据长度足够，交给帧解析器处理
        int consumedBytes = parseModbusFrame(currentData);

        // 情况3：解析器消费了部分/全部数据，将剩余数据写回缓冲区
        if (consumedBytes > 0) {
            byte[] remaining = new byte[currentData.length - consumedBytes];
            System.arraycopy(currentData, consumedBytes, remaining, 0, remaining.length);
            receiveBuffer.reset();
            receiveBuffer.write(remaining, 0, remaining.length);

            // 如果还剩数据，递归尝试继续解析（处理粘包）
            if (remaining.length > 0) {
                tryParseModbusBuffer();
            } else {
                // 数据刚好被完全消费，取消超时定时器
                if (modbusFrameTimer != null) modbusFrameTimer.cancel();
            }
        }
    }

    /**
     * 根据缓冲区头部数据，预判这一帧至少需要多少字节
     */
    private int calculateExpectedFrameLength(byte[] data) {
        if (data.length < 2) return -1;
        int funcCode = data[1] & 0xFF;
        if (funcCode > 0x80) return 5; // 异常响应固定5字节
        if (funcCode == 0x03) {
            if (data.length < 3) return -1; // 还没读到字节数字段
            int byteCount = data[2] & 0xFF;
            return 3 + byteCount + 2;
        }
        return -1; // 未知功能码
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
                    // 超时强制作为残帧处理
                    broadcastError("接收数据超时，可能存在残帧或CRC校验错误");
                    String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                    StringBuilder hex = new StringBuilder("[" + timestamp + " 残帧]: ");
                    for (byte b : snapshot) hex.append(String.format("%02X ", b));
                    broadcastTranslatedData(hex.toString());
                }
            }
        }, 100); // 100ms内如果下一截数据没来，判定为残帧
    }

    /**
     * 修改了返回值：返回成功消费(解析)了缓冲区中的多少个字节
     */
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
                i++; // 非法长度，跳过当前字节寻找下一个帧头
                continue;
            }

            if (bufferData.length - i >= expectedFrameLength) {
                byte[] completeFrame = new byte[expectedFrameLength];
                System.arraycopy(bufferData, i, completeFrame, 0, expectedFrameLength);

                if (Crc16Util.verifyCRC16(completeFrame, 0, expectedFrameLength)) {
                    byte[] pureData = new byte[expectedFrameLength - 2];
                    System.arraycopy(completeFrame, 0, pureData, 0, expectedFrameLength - 2);

                    String translated = ModbusUtils.parseModbusFrame(pureData);
                    if (translated != null && !translated.isEmpty()) {
                        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                        broadcastTranslatedData("[" + timestamp + " 接收]:\n" + translated);
                    }
                    i += expectedFrameLength; // 成功消费一帧
                } else {
                    broadcastError("Modbus CRC校验失败");
                    i++; // 校验失败，跳过当前字节继续找
                }
            } else {
                break; // 长度不够，等待下次拼接
            }
        }
        return i; // 返回消费的字节数
    }

    private void broadcastRawData(String text) { for (SerialEventListener l : listeners) l.onRawData(text); }
    private void broadcastTranslatedData(String text) { for (SerialEventListener l : listeners) l.onTranslatedData(text); }
    private void broadcastSystemLog(String log) { for (SerialEventListener l : listeners) l.onSystemLog(log); }
    private void broadcastError(String error) { for (SerialEventListener l : listeners) l.onError(error); }

    public boolean exportTextData(String content, String initialFileName, Window ownerWindow) {
        if (content == null || content.isEmpty()) return false;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出数据");
        fileChooser.setInitialFileName(initialFileName);
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("文本文件", "*.txt"));
        File file = fileChooser.showSaveDialog(ownerWindow);
        if (file != null) {
            try { Files.writeString(file.toPath(), content, StandardCharsets.UTF_8); return true; }
            catch (IOException e) { broadcastError("导出失败: " + e.getMessage()); return false; }
        }
        return false;
    }

    public String importTextData(Window ownerWindow) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导入数据");
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("文本文件", "*.txt"));
        File file = fileChooser.showOpenDialog(ownerWindow);
        if (file != null) {
            try { return Files.readString(file.toPath(), StandardCharsets.UTF_8); }
            catch (IOException e) { broadcastError("导入失败: " + e.getMessage()); return null; }
        }
        return null;
    }

    public void clearTextAreas(TextArea... textAreas) {
        for (TextArea ta : textAreas) { if (ta != null) ta.clear(); }
    }
}
