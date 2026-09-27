package org.example;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * 主界面控制器
 *
 * ★ 新增：设备地址输入框（十进制 0~255）
 *   - initialize 时默认设为 1
 *   - openSerialPort 前校验并设置到 SerialManager
 *   - 连接期间锁定输入框，关闭串口后恢复
 */
public class SerialUIController implements Initializable {

    // ==========================================
    // 1. 控件注入
    // ==========================================
    @FXML private ComboBox<String> portComboBox;
    @FXML private ComboBox<String> baudRateComboBox;
    @FXML private Button btnOpenPort;
    @FXML private Button btnClosePort;
    @FXML private Button btnRefresh;
    @FXML private Button btnClearRecv;
    @FXML private Button btnExportData;
    @FXML private Button btnOpenDebug;
    @FXML private MenuButton menuRecords;
    @FXML public  Button btnParameters;
    @FXML private TextArea txtRecvArea;
    @FXML private TextArea txtSendData;
    @FXML private Button btnImportData;
    @FXML private Button btnSend;

    // ★ 新增：设备地址输入框（十进制）
    @FXML private TextField txtDeviceAddress;

    // ==========================================
    // 2. 核心引擎
    // ==========================================
    private SerialManager manager;

    // ==========================================
    // 3. 初始化
    // ==========================================
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        manager = SerialManager.getInstance();
        baudRateComboBox.getItems().addAll("9600", "19200", "38400", "57600", "115200");
        btnClosePort.setDisable(true);

        // ★ 默认设备地址 = 1
        if (txtDeviceAddress != null) {
            txtDeviceAddress.setText("1");
            // ★ 限制只能输入数字
            txtDeviceAddress.setTextFormatter(new TextFormatter<>(change -> {
                String newText = change.getControlNewText();
                if (newText.isEmpty() || newText.matches("\\d{1,3}")) return change;
                return null;
            }));
        }
        try {
            manager.setDeviceAddress(1);
        } catch (IllegalArgumentException ignored) { }

        setupListeners();
        refreshPorts();
    }

    private void setupListeners() {
        manager.addListener(new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                // 主界面不显示底层Hex
            }

            @Override
            public void onTranslatedData(String translatedText) {
                Platform.runLater(() -> appendText(translatedText + "\n\n"));
            }

            @Override
            public void onSystemLog(String log) {
                Platform.runLater(() -> appendText("[系统] " + log + "\n\n"));
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> appendText("[错误] " + error + "\n\n"));
            }
        });
    }

    // ==========================================
    // 4. 串口连接操作
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

        // ★ 解析并校验十进制设备地址
        String addrText = txtDeviceAddress == null ? null : txtDeviceAddress.getText();
        try {
            if (addrText == null || addrText.trim().isEmpty()) {
                manager.setDeviceAddress((Integer) null);   // 不替换
            } else {
                int v = Integer.parseInt(addrText.trim());
                if (v < 0 || v > 255) {
                    showAlert(Alert.AlertType.ERROR, "地址错误",
                            "设备地址必须在 0~255 之间！");
                    return;
                }
                manager.setDeviceAddress(v);
            }
        } catch (NumberFormatException ex) {
            showAlert(Alert.AlertType.ERROR, "地址错误",
                    "设备地址必须是十进制数字(0~255)！");
            return;
        }

        int baudRate = Integer.parseInt(baudRateStr);

        if (manager.openPort(portName, baudRate)) {
            btnOpenPort.setDisable(true);
            btnClosePort.setDisable(false);
            portComboBox.setDisable(true);
            baudRateComboBox.setDisable(true);
            if (txtDeviceAddress != null) txtDeviceAddress.setDisable(true);   // ★ 锁定

            Integer addr = manager.getDeviceAddress();
            if (addr != null) {
                appendText(String.format("[系统] 设备地址已设为: %d (HEX: %02X)\n\n",
                        addr, addr));
            } else {
                appendText("[系统] 未设置设备地址，发送时将保留原首字节\n\n");
            }
        } else {
            showAlert(Alert.AlertType.ERROR, "错误", "串口打开失败！可能被占用。");
        }
    }

    @FXML
    private void closeSerialPort() {
        manager.closePort();
        btnOpenPort.setDisable(false);
        btnClosePort.setDisable(true);
        portComboBox.setDisable(false);
        baudRateComboBox.setDisable(false);
        if (txtDeviceAddress != null) txtDeviceAddress.setDisable(false);      // ★ 恢复
    }

    // ==========================================
    // 5. 界面工具
    // ==========================================
    @FXML
    private void clearRecvText() {
        manager.clearTextAreas(txtRecvArea);
    }

    @FXML
    private void exportTxtData() {
        String content = txtRecvArea.getText();
        if (content.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "接收区为空，无数据可导出！");
            return;
        }

        String currentTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "主页面系统数据_" + currentTime + ".csv";
        boolean success = manager.exportTextData(content, fileName, btnExportData.getScene().getWindow());

        if (success) {
            showAlert(Alert.AlertType.INFORMATION, "成功", "数据已成功导出！");
        }
    }

    @FXML
    private void importTxtData() {
        String content = manager.importTextData(btnImportData.getScene().getWindow());
        if (content != null) {
            txtSendData.setText(content);
        }
    }

    // ==========================================
    // 6. 快捷发送
    // ==========================================
    @FXML
    private void sendData() {
        if (!manager.isPortOpen()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先打开串口！");
            return;
        }

        String input = txtSendData.getText().trim();
        if (input.isEmpty()) return;

        sendHexCommand(input);
    }

    private void sendHexCommand(String hexStr) {
        if (hexStr == null || hexStr.trim().isEmpty()) return;

        try {
            String cleanHex = hexStr.replace(" ", "").replace("\n", "").replace("\r", "");
            if (cleanHex.length() % 2 != 0) {
                showAlert(Alert.AlertType.ERROR, "格式错误", "HEX格式错误，长度必须为偶数！");
                return;
            }

            byte[] dataToSend = new byte[cleanHex.length() / 2];
            for (int i = 0; i < dataToSend.length; i++) {
                int high = Character.digit(cleanHex.charAt(i * 2), 16);
                int low = Character.digit(cleanHex.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) {
                    showAlert(Alert.AlertType.ERROR, "格式错误", "包含非HEX字符，请检查输入！");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            manager.setCrcEnabled(true);
            // 主界面发送不带 tag，走广播（或可改为带 tag，看需求）
            manager.sendData(dataToSend);

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "发送异常", e.getMessage());
        }
    }

    // ==========================================
    // 7. 打开其他窗口
    // ==========================================
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
            Stage stage = new Stage();
            stage.setTitle("底层通讯调试助手");
            stage.setScene(new Scene(root));
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

            openNewWindowWithController(root, "历史记录", controller);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void showParameters() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/parameters_view.fxml"));
            Parent root = loader.load();

            // ★ 获取控制器，绑定窗口关闭时的清理逻辑
            ParametersView controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("配置查看");
            stage.setScene(new Scene(root));
            // ★ 窗口关闭时移除监听器，防止累积
            stage.setOnHidden(e -> controller.onDestroy());
            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void openNewWindowWithController(Parent root, String title, Object controller) {
        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(new Scene(root));
        stage.show();
    }

    // ==========================================
    // 8. 内部辅助
    // ==========================================
    private void appendText(String text) {
        txtRecvArea.appendText(text);
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}