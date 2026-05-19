package org.example;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Timer;
import java.util.TimerTask;

public class SerialController {
    private final SerialAppGUI view;
    private final SerialService model;

    // 透传模式下的防抖缓冲区和定时器
    private final StringBuilder transparentBuffer = new StringBuilder();
    private Timer transparentTimer;

    public SerialController(SerialAppGUI view, SerialService model) {
        this.view = view;
        this.model = model;
        setupListeners();
        refreshPorts();
    }

    private void setupListeners() {
        view.getBtnRefresh().addActionListener(e -> refreshPorts());
        view.getBtnOpenPort().addActionListener(e -> openSerialPort());
        view.getBtnClosePort().addActionListener(e -> closeSerialPort());
        view.getBtnSend().addActionListener(e -> sendData());
        view.getBtnClearRecv().addActionListener(e -> view.clearRecvText());

        view.getChkEnableCrc().addActionListener(e -> model.setCrcEnabled(view.isCrcEnabled()));

        model.setDataListener(new SerialService.DataListener() {
            @Override
            public void onRawDataReceived(byte[] data) {
                // 透传模式：收到碎片，放入防抖缓冲区
                if (view.isRecvHexSelected()) {
                    for (byte b : data) transparentBuffer.append(String.format("%02X ", b));
                } else {
                    transparentBuffer.append(new String(data, StandardCharsets.UTF_8));
                }

                if (transparentTimer != null) transparentTimer.cancel();
                transparentTimer = new Timer();
                transparentTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        final String snapshot = transparentBuffer.toString().trim();
                        transparentBuffer.setLength(0);
                        if (!snapshot.isEmpty()) {
                            SwingUtilities.invokeLater(() -> {
                                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                                view.appendRecvText("[" + timestamp + " 接收]: " + snapshot + "\n");
                            });
                        }
                    }
                }, 50);
            }

            @Override
            public void onFrameReceived(byte[] frame) {
                // Modbus模式：直接显示
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                StringBuilder displayBuilder = new StringBuilder();
                if (view.isRecvHexSelected()) {
                    for (byte b : frame) displayBuilder.append(String.format("%02X ", b));
                } else {
                    displayBuilder.append(new String(frame, StandardCharsets.UTF_8));
                }
                if (displayBuilder.length() > 0) {
                    SwingUtilities.invokeLater(() ->
                            view.appendRecvText("[" + timestamp + " 接收响应]: " + displayBuilder.toString().trim() + "\n\n")
                    );
                }
            }

            @Override
            public void onModbusCrcError() {
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                SwingUtilities.invokeLater(() ->
                        view.appendRecvText("[" + timestamp + " 接收异常]: CRC校验失败！\n\n")
                );
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(view, message, "错误", JOptionPane.ERROR_MESSAGE)
                );
            }
        });
    }

    private void refreshPorts() {
        String[] ports = model.getAvailablePorts();
        view.refreshPortList(ports);
        if (ports.length == 0) {
            JOptionPane.showMessageDialog(view, "未发现串口设备！", "提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void openSerialPort() {
        String portName = (String) view.getPortComboBox().getSelectedItem();
        if (portName == null) return;
        int baudRate = Integer.parseInt((String) view.getBaudRateComboBox().getSelectedItem());
        model.setCrcEnabled(view.isCrcEnabled());

        if (model.openPort(portName, baudRate)) {
            view.setPortControlsEnabled(false);
            view.appendRecvText("[系统] 串口 " + portName + " 已打开\n\n");
        } else {
            JOptionPane.showMessageDialog(view, "串口打开失败！可能被占用。", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void closeSerialPort() {
        if (transparentTimer != null) {
            transparentTimer.cancel();
            transparentTimer = null;
            final String snapshot = transparentBuffer.toString().trim();
            transparentBuffer.setLength(0);
            if (!snapshot.isEmpty()) {
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                view.appendRecvText("[" + timestamp + " 接收]: " + snapshot + "\n");
            }
        }
        model.closePort();
        view.setPortControlsEnabled(true);
        view.appendRecvText("[系统] 串口已关闭\n\n");
    }

    private void sendData() {
        if (!model.isPortOpen()) return;
        String input = view.getSendData();
        if (input.isEmpty()) return;

        try {
            byte[] dataToSend;
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());

            if (view.isSendHexSelected()) {
                String hexStr = input.replace(" ", "").replace("\n", "").replace("\r", "");
                if (hexStr.length() % 2 != 0) {
                    JOptionPane.showMessageDialog(view, "HEX格式错误，长度必须为偶数！", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                dataToSend = new byte[hexStr.length() / 2];
                for (int i = 0; i < dataToSend.length; i++) {
                    int high = Character.digit(hexStr.charAt(i * 2), 16);
                    int low = Character.digit(hexStr.charAt(i * 2 + 1), 16);
                    dataToSend[i] = (byte) ((high << 4) | low);
                }
                StringBuilder hexBuilder = new StringBuilder();
                for (byte b : dataToSend) hexBuilder.append(String.format("%02X ", b));
                view.appendRecvText("[" + timestamp + " 发送HEX]: " + hexBuilder.toString().trim() + "\n");
            } else {
                dataToSend = input.getBytes(StandardCharsets.UTF_8);
                view.appendRecvText("[" + timestamp + " 发送ASCII]: " + input + "\n");
            }
            model.sendData(dataToSend);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(view, "发送失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
}
