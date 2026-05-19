package org.example;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;

public class SerialService {
    private SerialPort comPort;
    private OutputStream outputStream;
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private Timer readTimer;
    private DataListener dataListener;

    private boolean isCurrentCrcEnabled = true;
    private Timer modbusFrameTimer;

    public interface DataListener {
        void onRawDataReceived(byte[] data);
        void onFrameReceived(byte[] frame);
        void onModbusCrcError();
        void onError(String message);
    }

    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
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
            return true;
        }
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
        }
        comPort = null;
    }

    public boolean isPortOpen() {
        return comPort != null && comPort.isOpen();
    }

    public void sendData(byte[] data) throws IOException {
        if (outputStream != null) {
            outputStream.write(data);
            outputStream.flush();
        }
    }

    public void setCrcEnabled(boolean crcEnabled) {
        this.isCurrentCrcEnabled = crcEnabled;
    }

    private void startEventListening() {
        comPort.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }
            @Override
            public void serialEvent(SerialPortEvent event) {
                drainInputStream();
            }
        });
    }

    private void startHighFreqPolling() {
        readTimer = new Timer("ReadTimer", true);
        readTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                drainInputStream();
            }
        }, 10, 20);
    }

    private void drainInputStream() {
        try {
            while (comPort != null && comPort.isOpen() && comPort.bytesAvailable() > 0) {
                byte[] buffer = new byte[comPort.bytesAvailable()];
                int numRead = comPort.readBytes(buffer, buffer.length);
                if (numRead > 0) {
                    receiveBuffer.write(buffer, 0, numRead);
                }
            }

            if (receiveBuffer.size() > 0) {
                byte[] dataSnapshot = receiveBuffer.toByteArray();
                receiveBuffer.reset();

                if (isCurrentCrcEnabled) {
                    parseModbusFrame(dataSnapshot);
                    startModbusFrameTimeout();
                } else {
                    if (dataListener != null) {
                        dataListener.onRawDataReceived(dataSnapshot);
                    }
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

                    if (receiveBuffer.size() > 0 && dataListener != null) {
                        dataListener.onModbusCrcError();
                        receiveBuffer.reset();
                    }
                }
            }
        }, 50);
    }

    /**
     * 严格合规的 Modbus RTU 帧解析
     */
    private void parseModbusFrame(byte[] bufferData) {
        int i = 0;
        while (i < bufferData.length) {
            if (bufferData.length - i < 5) break; // 最短帧5字节

            int functionCode = bufferData[i + 1] & 0xFF;
            int expectedFrameLength = 0;

            if (functionCode > 0x80) {
                // 1. 异常响应帧：固定5字节
                expectedFrameLength = 5;
            } else {
                // 2. 正常响应帧（包含所有非异常功能码：01, 02, 03, 04, 05, 06, 0F, 10, 自定义码等）
                // 强制遵循 Modbus 规范：第3个字节代表后续数据的字节数
                if (bufferData.length - i < 3) break; // 连第3个字节都没收到，等待

                int dataByteCount = bufferData[i + 2] & 0xFF;
                // 帧长 = 地址(1) + 功能码(1) + 字节数标识(1) + 数据 + CRC(2)
                expectedFrameLength = 3 + dataByteCount + 2;
            }

            // 防御性编程：如果推算出的帧长异常大，说明第3字节是乱码，跳过当前字节
            if (expectedFrameLength > 256 || expectedFrameLength < 5) {
                i++;
                continue;
            }

            // 判断缓冲区数据是否满足一帧
            if (bufferData.length - i >= expectedFrameLength) {
                byte[] completeFrame = new byte[expectedFrameLength];
                System.arraycopy(bufferData, i, completeFrame, 0, expectedFrameLength);

                // 严格 CRC 校验
                if (Crc16Util.verifyCRC16(completeFrame, 0, expectedFrameLength)) {
                    if (dataListener != null) dataListener.onFrameReceived(completeFrame);
                    i += expectedFrameLength;
                } else {
                    // CRC校验失败，说明要么数据损坏，要么这根本不是 Modbus 帧
                    if (dataListener != null) dataListener.onModbusCrcError();
                    i++; // 滑动1字节，重新寻找帧头
                }
            } else {
                // 数据尚未接收完整，跳出循环等待
                break;
            }
        }

        // 将未处理完的残余数据回写回缓冲区
        if (i > 0 && i < bufferData.length) {
            byte[] remaining = new byte[bufferData.length - i];
            System.arraycopy(bufferData, i, remaining, 0, remaining.length);
            receiveBuffer.write(remaining, 0, remaining.length);
        }
    }
}
