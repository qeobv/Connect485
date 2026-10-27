package org.example;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.example.core.BoardProfile;

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

public class SerialManager {

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

    private SerialPort comPort;
    private OutputStream outputStream;
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private final Object receiveLock = new Object();
    private Timer readTimer;
    private Timer modbusFrameTimer;
    private boolean isCurrentCrcEnabled = true;

    // 记录最近一次成功打开的端口参数（用于自动重连）
    private volatile String lastPortName = null;
    private volatile int lastBaudRate = 9600;

    // 当前板卡系列档案（未显式设置时使用默认系列）
    private volatile BoardProfile currentProfile;

    public void setProfile(BoardProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile 不能为 null");
        this.currentProfile = profile;
    }

    public BoardProfile getProfile() {
        return currentProfile != null ? currentProfile : BoardProfiles.defaultProfile();
    }

    private final AtomicReference<String> activeSenderTag = new AtomicReference<>(null);
    private volatile long activeSenderTimestamp = 0L;
    private static final long SENDER_TAG_EXPIRE_MS = 3000L;

    private final AtomicReference<Integer> deviceAddress = new AtomicReference<>(null);

    // 最近一次收到数据的时间（用于发送前等待总线静默）
    private volatile long lastReceiveMillis = 0;
    // 最近一次写入完成的时间（用于保证两次发送的最小间隔）
    private volatile long lastSendMillis = 0;
    /** 发送前要求的接收静默时长（485 半双工：设备正在发时上位机写会失败） */
    private static final long IDLE_REQUIRED_MS = 50;
    /**
     * 两次发送之间的最小间隔：覆盖"上一条命令发出后设备正在回响应"的窗口
     * （响应传输中字节尚未到达上位机，仅凭接收静默判断会失效）。
     */
    private static final long SEND_GAP_MS = 100;
    /** 等待总线就绪的最大时长，超过后直接尝试写入 */
    private static final long MAX_IDLE_WAIT_MS = 300;

    // 轮询暂停
    private volatile boolean pollingPaused = false;
    public void pausePolling()  { pollingPaused = true; }
    public void resumePolling() { pollingPaused = false; }
    public boolean isPollingPaused() { return pollingPaused; }

    public void setDeviceAddress(Integer addr) {
        if (addr == null) { deviceAddress.set(null); return; }
        if (addr < 0 || addr > 255)
            throw new IllegalArgumentException("设备地址超出范围(0~255): " + addr);
        deviceAddress.set(addr);
    }

    public void setDeviceAddress(String addrText) {
        if (addrText == null || addrText.trim().isEmpty()) {
            deviceAddress.set(null);
            return;
        }
        try {
            setDeviceAddress(Integer.parseInt(addrText.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("设备地址必须是十进制数字(0~255): " + addrText);
        }
    }

    public Integer getDeviceAddress() { return deviceAddress.get(); }
    public boolean hasDeviceAddress()  { return deviceAddress.get() != null; }

    private byte[] applyDeviceAddress(byte[] data) {
        Integer addr = deviceAddress.get();
        if (addr == null || data == null || data.length == 0) return data;
        byte newAddr = (byte) (addr & 0xFF);
        if (data[0] == newAddr) return data;
        byte[] copy = data.clone();
        copy[0] = newAddr;
        return copy;
    }

    private final List<TaggedListener> listeners = new CopyOnWriteArrayList<>();

    public interface SerialEventListener {
        void onRawData(String rawHex);
        void onTranslatedData(String translatedText);
        void onSystemLog(String log);
        void onError(String error);
    }

    private static class TaggedListener {
        final String tag;
        final SerialEventListener listener;
        TaggedListener(String tag, SerialEventListener listener) {
            this.tag = tag;
            this.listener = listener;
        }
    }

    public void addListener(String pageTag, SerialEventListener listener) {
        if (listener == null) return;
        listeners.add(new TaggedListener(pageTag, listener));
    }

    @Deprecated
    public void addListener(SerialEventListener listener) { addListener(null, listener); }

    public void removeListener(SerialEventListener listener) {
        if (listener == null) return;
        listeners.removeIf(t -> t.listener == listener);
    }

    public void removeListenersByTag(String pageTag) {
        if (pageTag == null) return;
        listeners.removeIf(t -> pageTag.equals(t.tag));
    }

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

    private void doSend(byte[] data) throws IOException {
        if (outputStream == null) return;

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

        // 485 半双工：等待总线就绪再写。
        // 条件 1：接收已静默（设备发完数据）；
        // 条件 2：距上次发送已过最小间隔（覆盖设备响应仍在传输中的窗口）。
        waitForBusReady();

        // jSerialComm 2.10.x 底层一次写失败即抛超时异常（无内部重试）。
        // 写失败可能因为：485 总线忙、USB 适配器睡眠唤醒、设备复位中。
        // 递增间隔重试 5 次（20/50/100/200/200ms），给链路恢复留时间。
        int attempts = 0;
        long[] retryDelays = {20, 50, 100, 200, 200};
        while (true) {
            try {
                outputStream.write(finalDataToSend);
                outputStream.flush();
                lastSendMillis = System.currentTimeMillis();
                return;
            } catch (SerialPortTimeoutException e) {
                if (attempts >= retryDelays.length) {
                    broadcastError("写入串口超时：设备可能处于复位/睡眠中或仍在发送数据，"
                            + "请稍等数秒重试；同时检查设备电源、485 接线(A/B)、波特率设置");
                    throw e;
                }
                try {
                    Thread.sleep(retryDelays[attempts]);
                    attempts++;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 等待总线就绪（接收静默 + 发送间隔），上限 MAX_IDLE_WAIT_MS */
    private void waitForBusReady() {
        long deadline = System.currentTimeMillis() + MAX_IDLE_WAIT_MS;
        while (true) {
            long now = System.currentTimeMillis();
            boolean receiveIdle = now - lastReceiveMillis >= IDLE_REQUIRED_MS;
            boolean sendGapOk = now - lastSendMillis >= SEND_GAP_MS;
            if ((receiveIdle && sendGapOk) || now >= deadline) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void setCrcEnabled(boolean crcEnabled) { this.isCurrentCrcEnabled = crcEnabled; }

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
            if (t.tag == null
                    || "DebugView".equals(t.tag)   // ★ 调试窗口始终接收
                    || t.tag.equals(active)) {
                t.listener.onRawData(text);
            }
        }
    }

    public void broadcastTranslatedData(String text) {
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

    public String[] getAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) names[i] = ports[i].getSystemPortName();
        return names;
    }

    public boolean openPort(String portName, int baudRate) {
        comPort = SerialPort.getCommPort(portName);
        comPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        comPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (comPort.openPort()) {
            try { outputStream = comPort.getOutputStream(); }
            catch (Exception e) { e.printStackTrace(); }
            lastPortName = portName;
            lastBaudRate = baudRate;
            startEventListening();
            startHighFreqPolling();
            broadcastSystemLog("串口 " + portName + " 已打开");
            return true;
        }
        broadcastError("串口打开失败");
        return false;
    }

    /**
     * 自动重连：设备/适配器长时间静默后可能失联（睡眠），
     * 关闭后按原参数重开可恢复。
     */
    public boolean reopenPort() {
        String portName = lastPortName;
        int baudRate = lastBaudRate;
        if (portName == null) return false;
        closePort();
        return openPort(portName, baudRate);
    }

    public boolean isPortOpen() { return comPort != null && comPort.isOpen(); }

    public void closePort() {
        if (readTimer != null) { readTimer.cancel(); readTimer = null; }
        if (modbusFrameTimer != null) { modbusFrameTimer.cancel(); modbusFrameTimer = null; }

        try {
            if (outputStream != null) { outputStream.close(); outputStream = null; }
        } catch (IOException ex) { ex.printStackTrace(); }

        if (comPort != null && comPort.isOpen()) {
            comPort.closePort();
            comPort = null;
        }

        synchronized (receiveLock) {
            receiveBuffer.reset();
        }
        pollingPaused = false;
        lastReceiveMillis = 0;
        lastSendMillis = 0;

        broadcastSystemLog("串口已关闭，资源已释放");
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
            @Override public void run() { drainInputStream(); }
        }, 10, 20);
    }

    private void drainInputStream() {
        synchronized (receiveLock) {
            try {
                while (comPort != null && comPort.isOpen() && comPort.bytesAvailable() > 0) {
                    byte[] buffer = new byte[comPort.bytesAvailable()];
                    int numRead = comPort.readBytes(buffer, buffer.length);
                    if (numRead > 0) {
                        receiveBuffer.write(buffer, 0, numRead);
                        lastReceiveMillis = System.currentTimeMillis();
                    }
                }

                if (receiveBuffer.size() > 0) {
                    if (isCurrentCrcEnabled) {
                        tryParseModbusBuffer();
                    } else {
                        byte[] dataSnapshot = receiveBuffer.toByteArray();
                        receiveBuffer.reset();
                        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                        StringBuilder hexBuilder = new StringBuilder();
                        for (byte b : dataSnapshot) hexBuilder.append(String.format("%02X ", b));
                        broadcastRawData("[" + timestamp + " 接收(无CRC)]: " + hexBuilder.toString().trim());
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

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

    /**
     * 期望帧长（回退版）：
     *   0x03 / 0x64 / 0x66 都用标准公式 3 + byteCount + 2
     *   异常码 5 字节
     */
    private int calculateExpectedFrameLength(byte[] data) {
        if (data.length < 2) return -1;
        int funcCode = data[1] & 0xFF;

        if (funcCode > 0x80) return 5;

        if (funcCode == 0x03 || funcCode == 0x64 || funcCode == 0x66) {
            if (data.length < 3) return -1;
            int byteCount = data[2] & 0xFF;
            return 3 + byteCount + 2;
        }

        // ★ 0x06 / 0x05 / 0x0F / 0x10 都是固定或可算长度
        if (funcCode == 0x06 || funcCode == 0x05 || funcCode == 0x10) {
            return 8;   // 固定 8 字节
        }

        return -1;
    }

    private void startModbusFrameTimeout() {
        if (modbusFrameTimer != null) modbusFrameTimer.cancel();
        modbusFrameTimer = new Timer();
        modbusFrameTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (receiveLock) {
                    if (receiveBuffer.size() > 0) {
                        byte[] snapshot = receiveBuffer.toByteArray();
                        receiveBuffer.reset();
                        broadcastError("接收数据超时，可能存在残帧或CRC校验错误");
                        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                        StringBuilder hex = new StringBuilder("[" + timestamp + " 残帧]: ");
                        for (byte b : snapshot) hex.append(String.format("%02X ", b));
                        broadcastRawData(hex.toString());
                    }
                }
            }
        }, 300);
    }

    private int crcFailCount = 0;

    private int parseModbusFrame(byte[] bufferData) {
        int i = 0;
        int parsedFrames = 0;      // 本批数据中成功解析出的合法帧数
        boolean crcFailed = false; // 是否发生过 CRC 校验失败（滑动重同步）
        boolean incomplete = false;// 因数据未到齐（帧长不足/尾部残片）而中断
        while (i < bufferData.length) {
            if (bufferData.length - i < 5) {
                incomplete = true;   // 尾部不足一帧，可能还有数据在传输
                break;
            }

            int functionCode = bufferData[i + 1] & 0xFF;
            int expectedFrameLength;

            if (functionCode > 0x80) {
                expectedFrameLength = 5;
            } else if (functionCode == 0x03 || functionCode == 0x64 || functionCode == 0x66) {
                if (bufferData.length - i < 3) {
                    incomplete = true;
                    break;
                }
                int dataByteCount = bufferData[i + 2] & 0xFF;
                expectedFrameLength = 3 + dataByteCount + 2;
            } else if (functionCode == 0x06 || functionCode == 0x05 || functionCode == 0x10) {
                expectedFrameLength = 8;   // ★ 固定 8 字节
            } else {
                i++;
                continue;
            }

            if (expectedFrameLength > 256 || expectedFrameLength < 5) { i++; continue; }

            if (bufferData.length - i >= expectedFrameLength) {
                byte[] completeFrame = new byte[expectedFrameLength];
                System.arraycopy(bufferData, i, completeFrame, 0, expectedFrameLength);

                if (Crc16Util.verifyCRC16(completeFrame, 0, expectedFrameLength)) {
                    parsedFrames++;
                    crcFailCount = 0;
                    String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                    StringBuilder hex = new StringBuilder("[" + timestamp + " 接收]: ");
                    for (byte b : completeFrame) hex.append(String.format("%02X ", b));
                    broadcastRawData(hex.toString().trim());
                    i += expectedFrameLength;
                } else {
                    // 滑动一位重新尝试帧同步（设备复位时可能输出前导垃圾，
                    // 如 "55 D0 68 01 64 ..."），此处静默跳过不报错。
                    crcFailed = true;
                    crcFailCount++;

                    if (crcFailCount > 10) {
                        receiveBuffer.reset();
                        crcFailCount = 0;
                        return i;
                    }
                    i++;
                }
            } else {
                incomplete = true;   // 帧长不足：数据仍在传输中，等待后续字节
                break;
            }
        }

        // 只有"数据完整且一路 CRC 失败找不到任何合法帧"才报一次错误。
        // 数据分片传输中（incomplete）静默等待，超时由残帧机制兜底。
        if (parsedFrames == 0 && crcFailed && !incomplete) {
            StringBuilder dbg = new StringBuilder("接收数据CRC校验失败，无法定位帧头: ");
            int showLen = Math.min(bufferData.length, 16);
            for (int k = 0; k < showLen; k++) dbg.append(String.format("%02X ", bufferData[k]));
            broadcastError(dbg.toString());
        }
        return i;
    }

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
                new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg"));
        File file = fileChooser.showOpenDialog(ownerWindow);
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