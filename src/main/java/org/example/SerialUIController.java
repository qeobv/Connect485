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

    // ========== 内部 ==========
    private SerialManager manager;
    private SerialManager.SerialEventListener myListener;
    private Timeline poller;

    private static final String PAGE_TAG = "SerialUI";

    private static final double WINDOW_WIDTH  = 900;
    private static final double WINDOW_HEIGHT = 720;
    private static final double WINDOW_MIN_WIDTH  = 600;
    private static final double WINDOW_MIN_HEIGHT = 400;

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
                .unit("%")
                .title("阀门开度")
                .decimals(1)
                .thresholdVisible(true)
                .sections(
                        new Section(0, 80, Color.web("#22C55E")),
                        new Section(80, 100, Color.web("#EF4444"))
                )
                .build();

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
    //  解析响应并更新界面
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

        // 只处理 0x03 读保持寄存器响应
        if (funcCode != 0x03) return;

        int byteCount = data[2] & 0xFF;
        if (data.length < 3 + byteCount) return;

        // 期望读 6 个寄存器（40006~40011）
        if (byteCount < 12) return;

        int openRatio    = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);   // 40006
        int controlMode  = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);   // 40007
        int setRatio     = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);   // 40008
        int torque       = ((data[9] & 0xFF) << 8) | (data[10] & 0xFF);  // 40009
        int torqueUnit   = ((data[11] & 0xFF) << 8) | (data[12] & 0xFF); // 40010
        int speed        = ((data[13] & 0xFF) << 8) | (data[14] & 0xFF); // 40011

        openRatioGauge.setValue(openRatio / 10.0);
        openRatioGauge.setThreshold(setRatio / 10.0);

        String unit = switch (torqueUnit) {
            case 0 -> "N";
            case 1 -> "Nm";
            case 2 -> "kN";
            default -> "";
        };
        lblTorque.setText(torque + " " + unit);
        lblSpeed.setText(speed + " rpm");
        lblControlMode.setText(controlModeName(controlMode));
        lblValveState.setText(valveStateName(openRatio, setRatio));
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

    private String valveStateName(int current, int target) {
        int diff = Math.abs(current - target);
        if (diff <= 5) return "已到位";
        if (current < target) return "正在开";
        if (current > target) return "正在关";
        return "未知";
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
    //  轮询
    // ==========================================
    private void startPolling() {
        stopPolling();
        poller = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (!manager.isPortOpen()) return;
            try {
                Integer addr = manager.getDeviceAddress();
                if (addr == null) addr = 1;

                byte[] cmd = new byte[]{
                        (byte) (addr & 0xFF),
                        0x03,
                        0x00, 0x05,   // 40006
                        0x00, 0x06    // 6 个寄存器
                };
                manager.setCrcEnabled(true);
                manager.sendData(cmd, PAGE_TAG);
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

            // 重新注册监听器（closePort 会清空）
            manager.addListener(PAGE_TAG, myListener);

            startPolling();
        } else {
            showAlert(Alert.AlertType.ERROR, "错误", "串口打开失败！可能被占用。");
        }
    }

    @FXML
    void closeSerialPort() {
        stopPolling();

        manager.closePort();
        btnOpenPort.setDisable(false);
        btnClosePort.setDisable(true);
        portComboBox.setDisable(false);
        baudRateComboBox.setDisable(false);
        if (txtDeviceAddress != null) txtDeviceAddress.setDisable(false);
    }

    // ==========================================
    //  给定开度（暂未实现写指令）
    // ==========================================
    @FXML
    private void setOpenRatio() {
        // TODO: 写 40012 寄存器，等控制指令确定后实现
        showAlert(Alert.AlertType.INFORMATION, "提示",
                "给定开度功能暂未实现，等控制指令确定后开放。");
    }

    // ==========================================
    //  打开其他窗口
    // ==========================================
    @FXML
    private void showParameters() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/parameters_view.fxml"));
            Parent root = loader.load();
            ParametersView controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("配置查看");

            Scene scene = new Scene(root);
            applyStylesheet(scene);        // ★ 加载 CSS

            stage.setScene(scene);
            applyDefaultSize(stage);
            stage.setOnHidden(e -> controller.onDestroy());
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void showHistoryRecords() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/history_view.fxml"));
            Parent root = loader.load();
            HistoryRecordController controller = loader.getController();
            controller.setManager(manager);

            Stage stage = new Stage();
            stage.setTitle("历史记录");

            Scene scene = new Scene(root);
            applyStylesheet(scene);        // ★ 加载 CSS

            stage.setScene(scene);
            applyDefaultSize(stage);
            stage.show();
        } catch (IOException e) {
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
            applyStylesheet(scene);        // ★ 加载 CSS

            stage.setScene(scene);
            applyDefaultSize(stage);
            stage.setOnHidden(e -> controller.onDestroy());
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void openNewWindowWithController(Parent root, String title) {
        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(new Scene(root));
        applyDefaultSize(stage);
        stage.show();
    }

    private void applyDefaultSize(Stage stage) {
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);
        stage.setMinWidth(WINDOW_MIN_WIDTH);
        stage.setMinHeight(WINDOW_MIN_HEIGHT);
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
    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}