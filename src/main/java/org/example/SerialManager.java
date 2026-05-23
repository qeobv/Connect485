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
                dataWithCrc[data.length] = (byte) (crc & 0xFF);
                dataWithCrc[data.length + 1] = (byte) ((crc >> 8) & 0xFF);
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
                byte[] dataSnapshot = receiveBuffer.toByteArray();
                receiveBuffer.reset();

                if (isCurrentCrcEnabled) {
                    parseModbusFrame(dataSnapshot);
                    startModbusFrameTimeout();
                } else {
                    String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                    broadcastTranslatedData("[" + timestamp + " 接收(无CRC)]: " + new String(dataSnapshot, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) { }
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
                    parseModbusFrame(snapshot);

                    if (receiveBuffer.size() > 0) {
                        broadcastError("接收数据残留，可能存在CRC校验错误或非标准帧");
                        receiveBuffer.reset();
                    }
                }
            }
        }, 50);
    }

    private void parseModbusFrame(byte[] bufferData) {
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
                    byte[] pureData = new byte[expectedFrameLength - 2];
                    System.arraycopy(completeFrame, 0, pureData, 0, expectedFrameLength - 2);

                    String translated = ModbusUtils.parseModbusFrame(pureData);
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

        if (i > 0 && i < bufferData.length) {
            byte[] remaining = new byte[bufferData.length - i];
            System.arraycopy(bufferData, i, remaining, 0, remaining.length);
            receiveBuffer.write(remaining, 0, remaining.length);
        }
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
