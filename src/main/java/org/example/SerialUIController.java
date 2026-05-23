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
import java.nio.charset.StandardCharsets;
import java.util.ResourceBundle;

/**
 * 主界面控制器
 * 职责：串口连接控制、业务数据(翻译后)展示、系统日志展示、快捷发送指令
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

    @FXML private TextArea txtRecvArea;        // 翻译结果展示区
    @FXML private TextArea txtSendData;        // 新增：快捷发送区
    @FXML private Button btnImportData;        // 新增：导入报文按钮
    @FXML private Button btnSend;              // 新增：发送按钮

    // ==========================================
    // 2. 核心引擎引用
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

        setupListeners();
        refreshPorts();
    }

    private void setupListeners() {
        manager.addListener(new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                // 主界面不显示底层Hex报文
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

        int baudRate = Integer.parseInt(baudRateStr);

        if (manager.openPort(portName, baudRate)) {
            btnOpenPort.setDisable(true);
            btnClosePort.setDisable(false);
            portComboBox.setDisable(true);
            baudRateComboBox.setDisable(true);
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
    }

    // ==========================================
    // 5. 界面工具操作 (清空、导出、导入)
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

        String fileName = "业务翻译数据_" + System.currentTimeMillis() + ".txt";
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
    // 6. 快捷发送逻辑 (默认HEX格式+开启CRC)
    // ==========================================
    @FXML
    private void sendData() {
        if (!manager.isPortOpen()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先打开串口！");
            return;
        }

        String input = txtSendData.getText().trim();
        if (input.isEmpty()) return;

        try {
            // 主界面发送区强制按 HEX 格式解析
            String hexStr = input.replace(" ", "").replace("\n", "").replace("\r", "");
            if (hexStr.length() % 2 != 0) {
                showAlert(Alert.AlertType.ERROR, "格式错误", "HEX格式错误，长度必须为偶数！");
                return;
            }

            byte[] dataToSend = new byte[hexStr.length() / 2];
            for (int i = 0; i < dataToSend.length; i++) {
                int high = Character.digit(hexStr.charAt(i * 2), 16);
                int low = Character.digit(hexStr.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) {
                    showAlert(Alert.AlertType.ERROR, "格式错误", "包含非HEX字符，请检查输入！");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            // 主界面发送默认开启 CRC
            manager.setCrcEnabled(true);
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

    @FXML private void showHistoryRecords() { openNewWindow("/org/example/history_view.fxml", "历史记录"); }
    @FXML private void showPositionRecords() { openNewWindow("/org/example/position_view.fxml", "修改位置记录"); }
    @FXML private void showTorqueRecords() { openNewWindow("/org/example/torque_view.fxml", "修改过力矩记录"); }
    @FXML private void showCommandRecords() { openNewWindow("/org/example/command_view.fxml", "指令记录"); }

    private void openNewWindow(String fxmlPath, String title) {
        try {
            URL fxmlLocation = getClass().getResource(fxmlPath);
            if (fxmlLocation == null) {
                showAlert(Alert.AlertType.ERROR, "错误", "找不到界面文件：" + fxmlPath);
                return;
            }
            FXMLLoader loader = new FXMLLoader(fxmlLocation);
            Parent root = loader.load();
            Stage newStage = new Stage();
            newStage.setTitle(title);
            newStage.setScene(new Scene(root));
            newStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    // 8. 内部辅助方法
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
