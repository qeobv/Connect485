package org.example;

import javax.swing.*;
import java.awt.*;

public class SerialAppGUI extends JFrame {
    private JComboBox<String> portComboBox;
    private JComboBox<String> baudRateComboBox;
    private JButton btnOpenPort;
    private JButton btnClosePort;
    private JButton btnRefresh;

    private JRadioButton rbSendHex;
    private JRadioButton rbSendAscii;
    private JTextArea txtSendData;
    private JButton btnSend;

    private JRadioButton rbRecvHex;
    private JRadioButton rbRecvAscii;
    // 删除了 rbRecvRaw
    private JCheckBox chkEnableCrc; // 新增：是否启用CRC校验的复选框
    private JButton btnClearRecv;
    private JTextArea txtRecvArea;

    public SerialAppGUI() {
        setTitle("Java 串口调试助手 (支持CRC校验开关)");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initUI();
    }

    private void initUI() {
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(BorderFactory.createTitledBorder("串口配置"));

        topPanel.add(new JLabel("选择端口:"));
        portComboBox = new JComboBox<>();
        topPanel.add(portComboBox);

        btnRefresh = new JButton("刷新");
        topPanel.add(btnRefresh);

        topPanel.add(new JLabel("波特率:"));
        baudRateComboBox = new JComboBox<>(new String[]{"9600", "19200", "38400", "57600", "115200"});
        topPanel.add(baudRateComboBox);

        btnOpenPort = new JButton("打开串口");
        topPanel.add(btnOpenPort);

        btnClosePort = new JButton("关闭串口");
        btnClosePort.setEnabled(false);
        topPanel.add(btnClosePort);

        JPanel sendPanel = new JPanel(new BorderLayout(5, 5));
        sendPanel.setBorder(BorderFactory.createTitledBorder("数据发送"));

        JPanel sendModePanel = new JPanel();
        sendModePanel.setLayout(new BoxLayout(sendModePanel, BoxLayout.Y_AXIS));

        rbSendHex = new JRadioButton("HEX发送", true);
        rbSendAscii = new JRadioButton("ASCII发送", false);
        ButtonGroup sendGroup = new ButtonGroup();
        sendGroup.add(rbSendHex);
        sendGroup.add(rbSendAscii);

        btnSend = new JButton("发送");
        btnSend.setEnabled(false);

        sendModePanel.add(rbSendHex);
        sendModePanel.add(rbSendAscii);
        sendModePanel.add(Box.createVerticalStrut(10));
        sendModePanel.add(btnSend);

        txtSendData = new JTextArea();
        txtSendData.setLineWrap(true);
        txtSendData.setWrapStyleWord(true);
        txtSendData.setRows(2);
        txtSendData.setFont(new Font("Monospaced", Font.PLAIN, 13));
        txtSendData.setText("01 03 00 00 00 01 84 0A");

        JScrollPane sendScrollPane = new JScrollPane(txtSendData);
        sendPanel.add(sendModePanel, BorderLayout.WEST);
        sendPanel.add(sendScrollPane, BorderLayout.CENTER);

        JPanel recvPanel = new JPanel(new BorderLayout(5, 5));
        recvPanel.setBorder(BorderFactory.createTitledBorder("数据接收 (按Modbus帧长度拼接)"));

        JPanel recvCtrlPanel = new JPanel();
        recvCtrlPanel.setLayout(new BoxLayout(recvCtrlPanel, BoxLayout.Y_AXIS));

        rbRecvHex = new JRadioButton("HEX显示", true);
        rbRecvAscii = new JRadioButton("ASCII显示", false);
        // 删除了 rbRecvRaw 相关代码
        ButtonGroup recvGroup = new ButtonGroup();
        recvGroup.add(rbRecvHex);
        recvGroup.add(rbRecvAscii);

        chkEnableCrc = new JCheckBox("启用CRC校验", true); // 新增：默认启用校验
        btnClearRecv = new JButton("清空");

        recvCtrlPanel.add(rbRecvHex);
        recvCtrlPanel.add(rbRecvAscii);
        recvCtrlPanel.add(Box.createVerticalStrut(10));
        recvCtrlPanel.add(chkEnableCrc); // 添加复选框
        recvCtrlPanel.add(Box.createVerticalStrut(10));
        recvCtrlPanel.add(btnClearRecv);

        txtRecvArea = new JTextArea();
        txtRecvArea.setEditable(false);
        txtRecvArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        txtRecvArea.setLineWrap(true);
        txtRecvArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(txtRecvArea);
        recvPanel.add(recvCtrlPanel, BorderLayout.WEST);
        recvPanel.add(scrollPane, BorderLayout.CENTER);

        setLayout(new BorderLayout(5, 5));
        add(topPanel, BorderLayout.NORTH);
        add(sendPanel, BorderLayout.SOUTH);
        add(recvPanel, BorderLayout.CENTER);
    }

    // --- 供 Controller 调用的视图更新方法 ---

    public void refreshPortList(String[] portNames) {
        portComboBox.removeAllItems();
        for (String port : portNames) {
            portComboBox.addItem(port);
        }
    }

    public void setPortControlsEnabled(boolean enabled) {
        btnOpenPort.setEnabled(enabled);
        btnClosePort.setEnabled(!enabled);
        btnSend.setEnabled(!enabled);
        portComboBox.setEnabled(enabled);
        baudRateComboBox.setEnabled(enabled);
    }

    public void appendRecvText(String text) {
        SwingUtilities.invokeLater(() -> txtRecvArea.append(text));
    }

    public void clearRecvText() {
        txtRecvArea.setText("");
    }

    // --- Getters ---

    public JComboBox<String> getPortComboBox() { return portComboBox; }
    public JComboBox<String> getBaudRateComboBox() { return baudRateComboBox; }
    public JButton getBtnOpenPort() { return btnOpenPort; }
    public JButton getBtnClosePort() { return btnClosePort; }
    public JButton getBtnSend() { return btnSend; }
    public JButton getBtnClearRecv() { return btnClearRecv; }
    public JButton getBtnRefresh() { return btnRefresh; }

    public boolean isSendHexSelected() { return rbSendHex.isSelected(); }
    public boolean isRecvHexSelected() { return rbRecvHex.isSelected(); }
    public boolean isRecvAsciiSelected() { return rbRecvAscii.isSelected(); }
    public boolean isCrcEnabled() { return chkEnableCrc.isSelected(); } // 新增 Getter

    public String getSendData() { return txtSendData.getText().trim(); }
    // 在 SerialAppGUI.java 的末尾 getter 区域添加：
    public JCheckBox getChkEnableCrc() { return chkEnableCrc; }

}
