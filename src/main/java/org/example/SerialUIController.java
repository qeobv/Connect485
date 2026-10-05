package org.example;

import eu.hansolo.medusa.Gauge;
import eu.hansolo.medusa.GaugeBuilder;
import eu.hansolo.medusa.Section;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

public class SerialUIController implements Initializable {

    // ========== 顶部控件 ==========
    @FXML private ComboBox<String> portComboBox;
    @FXML private ComboBox<String> baudRateComboBox;
    @FXML private Button btnOpenPort;
    @FXML private Button btnClosePort;
    @FXML private Button btnRefresh;
    @FXML private TextField txtDeviceAddress;

    // ========== 功能按钮 ==========
    @FXML private Button btnParameters;
    @FXML private MenuButton menuRecords;
    @FXML private Button btnOpenDebug;

    // ========== 仪表 ==========
    @FXML private StackPane gaugeContainer;
    private Gauge openRatioGauge;

    // ========== 数值标签 ==========
    @FXML private Label lblTorque;
    @FXML private Label lblSpeed;
    @FXML private Label lblControlMode;
    @FXML private Label lblValveState;

    // ========== 给定开度 ==========
    @FXML private TextField txtSetOpenRatio;
    @FXML private Button btnSetOpenRatio;

    // ========== 控制方式 ==========
    @FXML private ComboBox<String> cmbControlMode;
    @FXML private Button btnSetControlMode;

    // ========== 阀门控制按钮 ==========
    @FXML private Button btnOpenValve;
    @FXML private Button btnCloseValve;
    @FXML private Button btnStopValve;
    @FXML private Button btnResetAlarm;

    // ========== 内部 ==========
    private SerialManager manager;
    private SerialManager.SerialEventListener myListener;
    private Timeline poller;

    private static final String PAGE_TAG = "SerialUI";

    private static final double WINDOW_WIDTH  = 900;
    private static final double WINDOW_HEIGHT = 720;
    private static final double WINDOW_MIN_WIDTH  = 600;
    private static final double WINDOW_MIN_HEIGHT = 400;

    private volatile int currentValveState = -1;
    private volatile boolean waitingForResponse = false;

    // ==========================================
    //  初始化
    // ==========================================
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        manager = SerialManager.getInstance();

        baudRateComboBox.getItems().addAll("9600", "19200", "38400", "57600", "115200");
        btnClosePort.setDisable(true);

        if (txtDeviceAddress != null) {
            txtDeviceAddress.setText("1");
            txtDeviceAddress.setTextFormatter(new TextFormatter<>(change -> {
                String newText = change.getControlNewText();
                if (newText.isEmpty() || newText.matches("\\d{1,3}")) return change;
                return null;
            }));
        }
        try {
            manager.setDeviceAddress(1);
        } catch (IllegalArgumentException ignored) { }

        // 控制方式下拉
        if (cmbControlMode != null) {
            cmbControlMode.getItems().addAll(
                    "4-20mA", "电平型", "脉冲型", "二线制常开", "二线制常关",
                    "Modbus", "Profibus", "Hart", "压差PID");
            cmbControlMode.getSelectionModel().select("Modbus");
        }

        setupGauge();
        setupListener();
        refreshPorts();
    }

    // ==========================================
    //  仪表配置
    // ==========================================
    private void setupGauge() {
        openRatioGauge = GaugeBuilder.create()
                .skinType(Gauge.SkinType.GAUGE)
                .minValue(0)
                .maxValue(100)
                .title("阀门开度")
                .unit("%")
                .decimals(1)
                .thresholdVisible(true)
                .sections(
                        new Section(0, 20,  Color.web("#FDE68A")),
                        new Section(20, 80, Color.web("#86EFAC")),
                        new Section(80, 100, Color.web("#FCA5A5"))
                )
                .build();

        openRatioGauge.setSectionsVisible(true);
        openRatioGauge.setValue(0);
        openRatioGauge.setThreshold(0);

        if (gaugeContainer != null) {
            gaugeContainer.getChildren().add(openRatioGauge);
        }
    }

    // ==========================================
    //  串口监听
    // ==========================================
    private void setupListener() {
        myListener = new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                if (rawHex == null) return;
                if (rawHex.contains("发送]:")) return;
                if (!rawHex.contains("接收]:")) return;

                int idx = rawHex.indexOf("]:");
                String hex = rawHex.substring(idx + 2).replaceAll("[^0-9A-Fa-f]", "").toUpperCase();

                // 0x06 回显 → 恢复轮询
                if (hex.length() >= 4 && "06".equals(hex.substring(2, 4))) {
                    manager.resumePolling();
                    return;
                }

                waitingForResponse = false;
                Platform.runLater(() -> parseAndUpdate(rawHex));
            }

            @Override
            public void onTranslatedData(String translatedText) { }

            @Override
            public void onSystemLog(String log) {
                Platform.runLater(() -> System.out.println("[系统] " + log));
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> System.err.println("[错误] " + error));
            }
        };
        manager.addListener(PAGE_TAG, myListener);
    }

    // ==========================================
    //  解析响应（读 40002~40011，10 个寄存器）
    // ==========================================
    private void parseAndUpdate(String rawHex) {
        int idx = rawHex.indexOf("]:");
        if (idx < 0) return;
        String body = rawHex.substring(idx + 2).trim();
        String hex = body.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() < 10) return;

        byte[] data = hexToBytes(hex);
        if (data.length < 3) return;

        int funcCode = data[1] & 0xFF;
        if (funcCode != 0x03) return;

        int byteCount = data[2] & 0xFF;
        if (data.length < 3 + byteCount) return;
        if (byteCount < 20) return;   // 10 个寄存器 = 20 字节

        // 40002：状态位
        int statusReg    = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);

        // 40006：当前开度（偏移 11）
        int openRatio    = ((data[11] & 0xFF) << 8) | (data[12] & 0xFF);
        // 40007：控制方式
        int controlMode  = ((data[13] & 0xFF) << 8) | (data[14] & 0xFF);
        // 40008：给定开度
        int setRatio     = ((data[15] & 0xFF) << 8) | (data[16] & 0xFF);
        // 40009：力矩
        int torque       = ((data[17] & 0xFF) << 8) | (data[18] & 0xFF);
        // 40010：力矩单位
        int torqueUnit   = ((data[19] & 0xFF) << 8) | (data[20] & 0xFF);
        // 40011：转速
        int speed        = ((data[21] & 0xFF) << 8) | (data[22] & 0xFF);

        openRatioGauge.setValue(openRatio / 10.0);
        openRatioGauge.setThreshold(setRatio / 10.0);

        // 用 40002 的位判断状态
        boolean opening = ((statusReg >> 0) & 1) == 1;
        boolean closing = ((statusReg >> 1) & 1) == 1;
        boolean stopped = ((statusReg >> 2) & 1) == 1;

        if (opening) {
            currentValveState = 1;
        } else if (closing) {
            currentValveState = 2;
        } else {
            currentValveState = 0;
        }

        String unit = switch (torqueUnit) {
            case 0 -> "N";
            case 1 -> "Nm";
            case 2 -> "kN";
            default -> "";
        };
        lblTorque.setText(torque + " " + unit);
        lblSpeed.setText(speed + " rpm");
        lblControlMode.setText(controlModeName(controlMode));
        lblValveState.setText(valveStateName(currentValveState));
    }

    private String controlModeName(int mode) {
        return switch (mode) {
            case 0 -> "就地";
            case 1 -> "4-20mA";
            case 2 -> "电平型";
            case 3 -> "脉冲型";
            case 4 -> "二线制常开";
            case 5 -> "二线制常关";
            case 6 -> "Modbus";
            case 7 -> "Profibus";
            case 8 -> "Hart";
            case 9 -> "压差PID";
            default -> "未知";
        };
    }

    private String valveStateName(int state) {
        return switch (state) {
            case 0 -> "已停止";
            case 1 -> "正在开";
            case 2 -> "正在关";
            default -> "--";
        };
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ==========================================
    //  轮询（读 40002~40011，10 个寄存器）
    // ==========================================
    private void startPolling() {
        stopPolling();
        poller = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (!manager.isPortOpen()) return;
            if (manager.isPollingPaused()) return;
            if (waitingForResponse) return;

            try {
                Integer addr = manager.getDeviceAddress();
                if (addr == null) addr = 1;

                byte[] cmd = new byte[]{
                        (byte) (addr & 0xFF),
                        0x03,
                        0x00, 0x01,      // 40002
                        0x00, 0x0A       // 10 个寄存器
                };
                manager.setCrcEnabled(true);
                manager.sendData(cmd, PAGE_TAG);

                waitingForResponse = true;
                new Timeline(new KeyFrame(Duration.millis(1000), ev -> {
                    waitingForResponse = false;
                })).play();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    // ==========================================
    //  串口连接
    // ==========================================
    @FXML
    private void refreshPorts() {
        String[] ports = manager.getAvailablePorts();
        portComboBox.getItems().setAll(ports);
        if (ports.length == 0) {
            showAlert(Alert.AlertType.WARNING, "提示", "未发现串口设备！");
        }
    }

    @FXML
    private void openSerialPort() {
        String portName = portComboBox.getSelectionModel().getSelectedItem();
        String baudRateStr = baudRateComboBox.getSelectionModel().getSelectedItem();

        if (portName == null || baudRateStr == null) {
            showAlert(Alert.AlertType.ERROR, "错误", "请先选择串口和波特率！");
            return;
        }

        String addrText = txtDeviceAddress == null ? null : txtDeviceAddress.getText();
        try {
            if (addrText == null || addrText.trim().isEmpty()) {
                manager.setDeviceAddress((Integer) null);
            } else {
                int v = Integer.parseInt(addrText.trim());
                if (v < 0 || v > 255) {
                    showAlert(Alert.AlertType.ERROR, "地址错误", "设备地址必须在 0~255 之间！");
                    return;
                }
                manager.setDeviceAddress(v);
            }
        } catch (NumberFormatException ex) {
            showAlert(Alert.AlertType.ERROR, "地址错误", "设备地址必须是十进制数字(0~255)！");
            return;
        }

        int baudRate = Integer.parseInt(baudRateStr);

        if (manager.openPort(portName, baudRate)) {
            btnOpenPort.setDisable(true);
            btnClosePort.setDisable(false);
            portComboBox.setDisable(true);
            baudRateComboBox.setDisable(true);
            if (txtDeviceAddress != null) txtDeviceAddress.setDisable(true);

            manager.addListener(PAGE_TAG, myListener);

            currentValveState = -1;
            waitingForResponse = false;

            startPolling();
        } else {
            showAlert(Alert.AlertType.ERROR, "错误", "串口打开失败！可能被占用。");
        }
    }

    @FXML
    void closeSerialPort() {
        stopPolling();
        currentValveState = -1;
        waitingForResponse = false;

        manager.closePort();
        btnOpenPort.setDisable(false);
        btnClosePort.setDisable(true);
        portComboBox.setDisable(false);
        baudRateComboBox.setDisable(false);
        if (txtDeviceAddress != null) txtDeviceAddress.setDisable(false);
    }

    // ==========================================
    //  给定开度
    // ==========================================
    @FXML
    private void setOpenRatio() {
        if (!manager.isPortOpen()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先打开串口！");
            return;
        }

        String text = txtSetOpenRatio.getText();
        if (text == null || text.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请输入给定开度（0~100）");
            return;
        }

        int percent;
        try {
            percent = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            showAlert(Alert.AlertType.ERROR, "格式错误", "给定开度必须是整数");
            return;
        }

        if (percent < 0 || percent > 100) {
            showAlert(Alert.AlertType.ERROR, "范围错误", "给定开度必须在 0~100 之间");
            return;
        }

        int raw = percent * 10;
        sendControlCommand(0x000B, raw, "给定开度 " + percent + "%");
    }

    // ==========================================
    //  控制方式
    // ==========================================
    @FXML
    private void setControlMode() {
        if (cmbControlMode == null) return;
        String selected = cmbControlMode.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "提示", "请选择控制方式");
            return;
        }

        int value = switch (selected) {
            case "4-20mA" -> 1;
            case "电平型" -> 2;
            case "脉冲型" -> 3;
            case "二线制常开" -> 4;
            case "二线制常关" -> 5;
            case "Modbus" -> 6;
            case "Profibus" -> 7;
            case "Hart" -> 8;
            case "压差PID" -> 9;
            default -> 6;
        };

        sendControlCommand(0x000C, value, "控制方式: " + selected);
    }

    // ==========================================
    //  阀门控制
    // ==========================================
    @FXML
    private void openValve() {
        sendControlCommand(0x0003, 0x0001, "开阀");
    }

    @FXML
    private void closeValve() {
        sendControlCommand(0x0003, 0x0002, "关阀");
    }

    @FXML
    private void stopValve() {
        sendControlCommand(0x0003, 0x0004, "停止");
    }

    @FXML
    private void resetAlarm() {
        sendControlCommand(0x0004, 0x0001, "复归");
    }

    private void sendControlCommand(int regAddr, int value, String action) {
        if (!manager.isPortOpen()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先打开串口！");
            return;
        }

        Integer addr = manager.getDeviceAddress();
        if (addr == null) addr = 1;

        try {
            manager.pausePolling();

            byte[] cmd = new byte[]{
                    (byte) (addr & 0xFF),
                    0x06,
                    (byte) ((regAddr >> 8) & 0xFF),
                    (byte) (regAddr & 0xFF),
                    (byte) ((value >> 8) & 0xFF),
                    (byte) (value & 0xFF)
            };

            manager.setCrcEnabled(true);
            manager.sendData(cmd, PAGE_TAG);

            System.out.println("[控制] " + action + " 指令已发送: 寄存器 " + regAddr + " = " + value);

        } catch (Exception e) {
            manager.resumePolling();
            showAlert(Alert.AlertType.ERROR, "发送失败", e.getMessage());
        }
    }

    // ==========================================
    //  阀门停止检查
    // ==========================================
    private boolean isValveStopped() {
        if (currentValveState == -1) {
            showAlert(Alert.AlertType.WARNING, "提示", "尚未获取阀门状态，请稍候再试");
            return false;
        }
        if (currentValveState != 0) {
            showAlert(Alert.AlertType.WARNING, "阀门运行中",
                    "请等阀门停止后再打开此窗口");
            return false;
        }
        return true;
    }

    // ==========================================
    //  打开其他窗口
    // ==========================================
    @FXML
    private void showParameters() {
        if (!isValveStopped()) return;

        manager.pausePolling();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/parameters_view.fxml"));
            Parent root = loader.load();
            ParametersView controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("配置查看");
            Scene scene = new Scene(root);
            applyStylesheet(scene);
            stage.setScene(scene);
            applyDefaultSize(stage);
            stage.setOnHidden(e -> {
                controller.onDestroy();
                manager.resumePolling();
            });
            stage.show();
        } catch (IOException e) {
            manager.resumePolling();
            e.printStackTrace();
        }
    }

    @FXML
    private void showHistoryRecords() {
        if (!isValveStopped()) return;

        manager.pausePolling();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/history_view.fxml"));
            Parent root = loader.load();
            HistoryRecordController controller = loader.getController();
            controller.setManager(manager);

            Stage stage = new Stage();
            stage.setTitle("历史记录");
            Scene scene = new Scene(root);
            applyStylesheet(scene);
            stage.setScene(scene);
            applyDefaultSize(stage);
            stage.setOnHidden(e -> manager.resumePolling());
            stage.show();
        } catch (IOException e) {
            manager.resumePolling();
            e.printStackTrace();
        }
    }

    @FXML
    private void openDebugWindow() {
        try {
            URL fxmlLocation = getClass().getResource("DebugView.fxml");
            if (fxmlLocation == null) {
                showAlert(Alert.AlertType.ERROR, "错误", "无法找到调试界面文件：DebugView.fxml");
                return;
            }
            FXMLLoader loader = new FXMLLoader(fxmlLocation);
            Parent root = loader.load();
            DebugViewController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("底层通讯调试助手");
            Scene scene = new Scene(root);
            applyStylesheet(scene);
            stage.setScene(scene);
            applyDefaultSize(stage);
            stage.setOnHidden(e -> controller.onDestroy());
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    //  工具
    // ==========================================
    private void applyStylesheet(Scene scene) {
        URL css = getClass().getResource("/org/example/style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    private void applyDefaultSize(Stage stage) {
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);
        stage.setMinWidth(WINDOW_MIN_WIDTH);
        stage.setMinHeight(WINDOW_MIN_HEIGHT);
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}