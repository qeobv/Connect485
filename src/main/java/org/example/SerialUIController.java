package org.example;

import eu.hansolo.medusa.Gauge;
import eu.hansolo.medusa.GaugeBuilder;
import eu.hansolo.medusa.Section;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.core.ControlAction;
import org.example.core.DeviceState;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

public class SerialUIController implements Initializable {

    // ========== 顶部控件 ==========
    @FXML private ComboBox<String> portComboBox;
    @FXML private ComboBox<String> baudRateComboBox;
    @FXML private ComboBox<String> cmbBoardSeries;
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
    @FXML private VBox speedBox;
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
    private PauseTransition responseTimeout;

    private static final String PAGE_TAG = "SerialUI";

    private static final double WINDOW_WIDTH  = 900;
    private static final double WINDOW_HEIGHT = 720;
    private static final double WINDOW_MIN_WIDTH  = 600;
    private static final double WINDOW_MIN_HEIGHT = 400;

    private volatile int currentValveState = -1;
    private volatile boolean waitingForResponse = false;
    private int pollSendFailCount = 0;
    /** 配置/历史记录子窗口打开计数（打开期间禁止阀门控制命令） */
    private int subWindowCount = 0;

    // ==========================================
    //  初始化
    // ==========================================
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        manager = SerialManager.getInstance();
        manager.setProfile(BoardProfiles.defaultProfile());

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

        // 控制方式下拉（选项由板卡系列档案提供）
        if (cmbControlMode != null) {
            cmbControlMode.getItems().addAll(manager.getProfile().controlModeOptions());
            cmbControlMode.getSelectionModel().select("Modbus");
        }

        // 板卡系列下拉：填充已注册系列，切换系列时刷新控制方式选项与转速栏显隐
        if (cmbBoardSeries != null) {
            for (org.example.core.BoardProfile p : BoardProfiles.all()) {
                cmbBoardSeries.getItems().add(p.id() + " - " + p.displayName());
            }
            cmbBoardSeries.getSelectionModel().select(0);
            applyProfileUi(BoardProfiles.get(cmbBoardSeries.getSelectionModel().getSelectedItem().split(" - ")[0]));
            cmbBoardSeries.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                if (sel == null) return;
                String id = sel.split(" - ")[0];
                org.example.core.BoardProfile profile = BoardProfiles.get(id);
                if (profile == null) return;
                manager.setProfile(profile);
                applyProfileUi(profile);
            });
        }

        setupGauge();
        setupListener();
        refreshPorts();
    }

    /** 按系列档案刷新界面：控制方式下拉选项 + 转速栏显隐 */
    private void applyProfileUi(org.example.core.BoardProfile profile) {
        if (profile == null) return;
        if (cmbControlMode != null) {
            cmbControlMode.getItems().setAll(profile.controlModeOptions());
            if (profile.controlModeOptions().contains("Modbus")) {
                cmbControlMode.getSelectionModel().select("Modbus");
            } else if (!profile.controlModeOptions().isEmpty()) {
                cmbControlMode.getSelectionModel().select(0);
            }
        }
        if (speedBox != null) {
            boolean hasSpeed = profile.hasSpeed();
            speedBox.setVisible(hasSpeed);
            speedBox.setManaged(hasSpeed);
        }
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

                byte[] data = hexToBytes(hex);
                DeviceState state = manager.getProfile().parsePollResponse(data);
                if (state != null) {
                    Platform.runLater(() -> updateDisplay(state));
                }
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
    //  显示轮询解析结果（解析逻辑在 BoardProfile 实现中）
    // ==========================================
    private void updateDisplay(DeviceState state) {
        openRatioGauge.setValue(state.openRatio);
        openRatioGauge.setThreshold(state.setRatio);

        lblTorque.setText(state.torqueText);
        if (state.speedText != null) {
            lblSpeed.setText(state.speedText);
        }
        lblControlMode.setText(state.controlModeText);
        lblValveState.setText(state.valveStateText);

        // 更新"阀门是否停止"标记（供打开配置/历史页前检查）
        currentValveState = switch (state.valveState) {
            case OPENING -> 1;
            case CLOSING -> 2;
            case STOPPED -> 0;
            default -> currentValveState;
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
        pollSendFailCount = 0;
        poller = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (!manager.isPortOpen()) return;
            if (manager.isPollingPaused()) return;
            if (waitingForResponse) return;

            try {
                Integer addr = manager.getDeviceAddress();
                if (addr == null) addr = 1;

                byte[] cmd = manager.getProfile().buildPollCommand(addr);
                manager.setCrcEnabled(true);
                manager.sendData(cmd, PAGE_TAG);
                pollSendFailCount = 0;

                waitingForResponse = true;
                if (responseTimeout != null) responseTimeout.stop();
                responseTimeout = new PauseTransition(Duration.millis(1000));
                responseTimeout.setOnFinished(ev -> waitingForResponse = false);
                responseTimeout.play();
            } catch (Exception ex) {
                pollSendFailCount++;
                System.err.println("[轮询发送失败 x" + pollSendFailCount + "] " + ex.getMessage());
                if (pollSendFailCount >= 10) {
                    // 连续写失败：设备/适配器可能长时间静默后失联（睡眠），
                    // 先自动重连一次（关闭后按原参数重开），仍失败再提示用户。
                    stopPolling();
                    Platform.runLater(() -> {
                        boolean recovered = manager.reopenPort();
                        if (recovered) {
                            System.out.println("[系统] 串口自动重连成功");
                            startPolling();
                        } else {
                            showAlert(Alert.AlertType.ERROR, "串口写入失败",
                                    "连续 10 次写入串口超时，自动重连失败。\n\n"
                                            + "请检查：\n"
                                            + "1. 设备是否上电\n"
                                            + "2. 485 接线是否正确（A 接 A、B 接 B）\n"
                                            + "3. 波特率是否与设备一致\n"
                                            + "4. 串口是否被其他程序占用\n\n"
                                            + "检查后请重新点击【连接串口】");
                        }
                    });
                }
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
        if (responseTimeout != null) {
            responseTimeout.stop();
            responseTimeout = null;
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
            if (cmbBoardSeries != null) cmbBoardSeries.setDisable(true);
            if (txtDeviceAddress != null) txtDeviceAddress.setDisable(true);

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
        if (cmbBoardSeries != null) cmbBoardSeries.setDisable(false);
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
        sendControlCommand(ControlAction.SET_OPEN_RATIO, raw, "给定开度 " + percent + "%");
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

        int value = manager.getProfile().controlModeValue(selected);
        sendControlCommand(ControlAction.SET_CONTROL_MODE, value, "控制方式: " + selected);
    }

    // ==========================================
    //  阀门控制
    // ==========================================
    @FXML
    private void openValve() {
        sendControlCommand(ControlAction.OPEN_VALVE, 0, "开阀");
    }

    @FXML
    private void closeValve() {
        sendControlCommand(ControlAction.CLOSE_VALVE, 0, "关阀");
    }

    @FXML
    private void stopValve() {
        sendControlCommand(ControlAction.STOP_VALVE, 0, "停止");
    }

    @FXML
    private void resetAlarm() {
        sendControlCommand(ControlAction.RESET_ALARM, 0, "复归");
    }

    private void sendControlCommand(ControlAction action, int value, String actionText) {
        if (!manager.isPortOpen()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先打开串口！");
            return;
        }
        if (subWindowCount > 0) {
            showAlert(Alert.AlertType.WARNING, "提示",
                    "配置/历史记录窗口打开中，请先关闭该窗口再操作阀门");
            return;
        }

        Integer addr = manager.getDeviceAddress();
        if (addr == null) addr = 1;

        try {
            manager.pausePolling();

            byte[] cmd = manager.getProfile().buildControlCommand(addr, action, value);
            manager.setCrcEnabled(true);
            manager.sendData(cmd, PAGE_TAG);

            System.out.println("[控制] " + actionText + " 指令已发送: " + action + " = " + value);

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
    /**
     * 禁用/恢复主界面的阀门控制控件。
     * 配置/历史页打开期间禁止开阀等操作，避免控制命令与
     * 0x64/0x66 大帧响应在 485 总线上冲突。
     */
    private void setValveControlsDisabled(boolean disabled) {
        if (btnOpenValve != null) btnOpenValve.setDisable(disabled);
        if (btnCloseValve != null) btnCloseValve.setDisable(disabled);
        if (btnStopValve != null) btnStopValve.setDisable(disabled);
        if (btnResetAlarm != null) btnResetAlarm.setDisable(disabled);
        if (btnSetOpenRatio != null) btnSetOpenRatio.setDisable(disabled);
        if (txtSetOpenRatio != null) txtSetOpenRatio.setDisable(disabled);
        if (btnSetControlMode != null) btnSetControlMode.setDisable(disabled);
        if (cmbControlMode != null) cmbControlMode.setDisable(disabled);
    }

    @FXML
    private void showParameters() {
        if (!isValveStopped()) return;

        manager.pausePolling();
        setValveControlsDisabled(true);
        subWindowCount++;
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
                subWindowCount--;
                manager.resumePolling();
                setValveControlsDisabled(false);
            });
            stage.show();
        } catch (IOException e) {
            subWindowCount--;
            manager.resumePolling();
            setValveControlsDisabled(false);
            e.printStackTrace();
        }
    }

    @FXML
    private void showHistoryRecords() {
        if (!isValveStopped()) return;

        manager.pausePolling();
        setValveControlsDisabled(true);
        subWindowCount++;
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
            stage.setOnHidden(e -> {
                subWindowCount--;
                manager.resumePolling();
                setValveControlsDisabled(false);
            });
            stage.show();
        } catch (IOException e) {
            subWindowCount--;
            manager.resumePolling();
            setValveControlsDisabled(false);
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