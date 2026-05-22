package org.example;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;

/**
 * FXML 控制器类，负责处理串口通信的业务逻辑
 * 通过 @FXML 注解与 SerialUI.fxml 界面文件绑定
 */
public class SerialUIController implements Initializable {

    // ==========================================
    // 1. 控件注入：fx:id 必须与 FXML 文件中定义的完全一致
    // ==========================================
    @FXML private ComboBox<String> portComboBox;      // 串口选择下拉框
    @FXML private ComboBox<String> baudRateComboBox;  // 波特率选择下拉框
    @FXML private Button btnOpenPort;                // 打开串口按钮
    @FXML private Button btnClosePort;               // 关闭串口按钮
    @FXML private Button btnSend;                    // 发送数据按钮
    @FXML private Button btnClearRecv;               // 清空接收区按钮
    @FXML private Button btnRefresh;                 // 刷新串口按钮
    @FXML private Button exportData;                 // 数据导出按钮
    @FXML private Button importData;                 // 数据导入按钮
    @FXML private RadioButton rbSendHex;             // 发送HEX格式单选按钮
    @FXML private RadioButton rbRecvHex;             // 接收HEX格式单选按钮
    @FXML private CheckBox chkEnableCrc;             // 启用CRC校验复选框

    @FXML private TextArea txtSendData;              // 发送数据文本区域
    @FXML private TextArea txtRecvArea;              // 接收数据文本区域

    // 新增的记录查看按钮
    @FXML private MenuButton menuRecords;            // 记录查看菜单按钮

    // ==========================================
    // 2. 模型与状态变量
    // ==========================================
    private SerialService model;                     // 串口服务模型
    private final StringBuilder transparentBuffer = new StringBuilder();  // 透明数据缓冲区
    private Timer transparentTimer;                  // 透明数据定时器

    // ==========================================
    // 3. 初始化方法（FXML 加载完成后自动调用）
    // ==========================================
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        model = new SerialService();                 // 初始化串口服务模型

        // 初始化波特率下拉框数据
        baudRateComboBox.setItems(FXCollections.observableArrayList("9600", "19200", "38400", "57600", "115200"));

        // 初始按钮状态
        btnClosePort.setDisable(true);               // 关闭串口按钮初始禁用
        btnSend.setDisable(true);                    // 发送按钮初始禁用

        setupListeners();                            // 设置事件监听器
        refreshPorts();                              // 初始化端口列表
    }

    // ==========================================
    // 4. 事件处理方法（可在 FXML 的 On Action 中指定，也可代码绑定）
    // ==========================================
    @FXML
    private void refreshPorts() {                    // 刷新可用串口列表
        String[] ports = model.getAvailablePorts();  // 获取可用串口
        portComboBox.getItems().clear();             // 清空当前列表
        portComboBox.getItems().addAll(ports);       // 添加新获取的串口列表
        if (ports.length == 0) {                     // 如果没有可用串口
            showAlert(AlertType.WARNING, "提示", "未发现串口设备！");  // 显示警告信息
        }
    }

    @FXML
    private void openSerialPort() {                  // 打开串口方法
        String portName = portComboBox.getSelectionModel().getSelectedItem();
        if (portName == null) return;                // 如果没有选择串口则直接返回

        Object baudRateItem = baudRateComboBox.getSelectionModel().getSelectedItem();
        if (baudRateItem == null) {                  // 如果没有选择波特率
            showAlert(AlertType.ERROR, "错误", "请选择波特率！");  // 显示错误信息
            return;
        }
        int baudRate = Integer.parseInt(baudRateItem.toString());  // 解析波特率值
        model.setCrcEnabled(chkEnableCrc.isSelected());  // 设置CRC校验状态

        if (model.openPort(portName, baudRate)) {    // 尝试打开串口
            btnOpenPort.setDisable(true);            // 打开成功后禁用打开按钮
            btnClosePort.setDisable(false);          // 启用关闭按钮
            btnSend.setDisable(false);               // 启用发送按钮
            portComboBox.setDisable(true);           // 禁用串口选择框
            baudRateComboBox.setDisable(true);       // 禁用波特率选择框
            appendRecvText("[系统] 串口 " + portName + " 已打开\n\n");  // 显示打开成功信息
        } else {                                    // 打开失败
            showAlert(AlertType.ERROR, "错误", "串口打开失败！可能被占用。");  // 显示错误信息
        }
    }

    @FXML
    private void closeSerialPort() {                 // 关闭串口方法
        if (transparentTimer != null) {              // 如果存在透明数据定时器
            transparentTimer.cancel();               // 取消定时器
            transparentTimer = null;                 // 清空定时器引用
            final String snapshot = transparentBuffer.toString().trim();  // 获取缓冲区快照
            transparentBuffer.setLength(0);          // 清空缓冲区
            if (!snapshot.isEmpty()) {               // 如果快照不为空
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                appendRecvText("[" + timestamp + " 接收]: " + snapshot + "\n");  // 显示快照内容
            }
        }
        model.closePort();                           // 关闭串口

        btnOpenPort.setDisable(false);              // 启用打开按钮
        btnClosePort.setDisable(true);              // 禁用关闭按钮
        btnSend.setDisable(true);                   // 禁用发送按钮
        portComboBox.setDisable(false);             // 启用串口选择框
        baudRateComboBox.setDisable(false);         // 启用波特率选择框
        appendRecvText("[系统] 串口已关闭\n\n");    // 显示关闭信息
    }

    @FXML
    private void sendData() {                        // 发送数据方法
        if (!model.isPortOpen()) return;            // 如果串口未打开则直接返回
        String input = txtSendData.getText().trim(); // 获取发送数据并去除首尾空格
        if (input.isEmpty()) return;                // 如果输入为空则直接返回

        try {
            byte[] dataToSend;                      // 待发送数据数组
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());

            if (rbSendHex.isSelected()) {            // 如果选择HEX格式发送
                String hexStr = input.replace(" ", "").replace("\n", "").replace("\r", "");
                if (hexStr.length() % 2 != 0) {     // 检查HEX字符串长度是否为偶数
                    showAlert(AlertType.ERROR, "错误", "HEX格式错误，长度必须为偶数！");
                    return;
                }
                dataToSend = new byte[hexStr.length() / 2];  // 创建字节数组
                for (int i = 0; i < dataToSend.length; i++) {
                    int high = Character.digit(hexStr.charAt(i * 2), 16);  // 解析高位
                    int low = Character.digit(hexStr.charAt(i * 2 + 1), 16);  // 解析低位
                    dataToSend[i] = (byte) ((high << 4) | low);  // 组合成字节
                }
                StringBuilder hexBuilder = new StringBuilder();
                for (byte b : dataToSend) hexBuilder.append(String.format("%02X ", b));  // 转换为HEX字符串
                appendRecvText("[" + timestamp + " 发送HEX]: " + hexBuilder.toString().trim() + "\n");
            } else {                                // 如果选择ASCII格式发送
                dataToSend = input.getBytes(StandardCharsets.UTF_8);  // 转换为字节数组
                appendRecvText("[" + timestamp + " 发送ASCII]: " + input + "\n");
            }
            model.sendData(dataToSend);              // 发送数据
        } catch (Exception e) {
            showAlert(AlertType.ERROR, "错误", "发送失败: " + e.getMessage());  // 显示错误信息
        }
    }

    @FXML
    private void clearRecvText() {                   // 清空接收区方法
        txtRecvArea.clear();                         // 清空文本区域内容
    }

    // ==========================================
    // 数据导入导出功能
    // ==========================================

    /**
     * 导出接收区的数据为 .txt 文件
     */
    @FXML
    private void exportTxtData() {
        String content = txtRecvArea.getText();
        if (content.isEmpty()) {
            showAlert(AlertType.WARNING, "提示", "接收区为空，无数据可导出！");
            return;
        }

        // 获取按钮所在的窗口用于挂载文件选择器
        Window stage = exportData.getScene().getWindow();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出数据为 TXT");
        fileChooser.setInitialFileName("串口数据_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(System.currentTimeMillis()));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                // 强制使用 UTF-8 编码写入，防止中文乱码
                Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
                showAlert(AlertType.INFORMATION, "成功", "数据已成功导出到:\n" + file.getAbsolutePath());
            } catch (IOException e) {
                showAlert(AlertType.ERROR, "错误", "导出文件失败:\n" + e.getMessage());
            }
        }
    }

    /**
     * 从 .txt 文件导入数据到发送区
     */
    @FXML
    private void importTxtData() {
        Window stage = importData.getScene().getWindow();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("从 TXT 导入数据");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                // 强制使用 UTF-8 编码读取
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                txtSendData.setText(content);
                showAlert(AlertType.INFORMATION, "成功", "数据已成功导入！");
            } catch (IOException e) {
                showAlert(AlertType.ERROR, "错误", "导入文件失败:\n" + e.getMessage());
            }
        }
    }

    private void openNewWindow(String fxmlPath, String title) {
        try {
            // 1. 加载 FXML 文件
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent root = loader.load();

            // 2. 创建一个新的 Stage（窗口）
            Stage newStage = new Stage();
            newStage.setTitle(title);
            newStage.setScene(new Scene(root));

            // 可选：设置窗口的模态状态（如果需要阻塞主窗口，取消下面一行的注释）
            // newStage.initModality(Modality.APPLICATION_MODAL);

            // 3. 显示新窗口
            newStage.show();

        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("无法加载页面: " + fxmlPath);
        }
    }

    // 以下是 MenuItem 绑定的点击事件
    @FXML
    private void showHistoryRecords(ActionEvent event) {
        // 请确保 resources 目录下有对应的 fxml 文件
        openNewWindow("/org/example/history_view.fxml", "历史记录");
    }

    @FXML
    private void showPositionRecords(ActionEvent event) {
        openNewWindow("/org/example/position_view.fxml", "修改位置记录");
    }

    @FXML
    private void showTorqueRecords(ActionEvent event) {
        openNewWindow("/org/example/torque_view.fxml", "修改过力矩记录");
    }

    @FXML
    private void showCommandRecords(ActionEvent event) {
        openNewWindow("/org/example/command_view.fxml", "指令记录");
    }

    // ==========================================
    // 5. 内部逻辑与监听器设置
    // ==========================================
    private void setupListeners() {                  // 设置监听器方法
        chkEnableCrc.setOnAction(e -> model.setCrcEnabled(chkEnableCrc.isSelected()));  // CRC校验状态改变监听

        model.setDataListener(new SerialService.DataListener() {  // 设置数据监听器
            @Override
            public void onRawDataReceived(byte[] data) {  // 原始数据接收回调
                if (rbRecvHex.isSelected()) {       // 如果选择HEX显示
                    for (byte b : data) transparentBuffer.append(String.format("%02X ", b));  // 添加为HEX格式
                } else {                             // 如果选择ASCII显示
                    transparentBuffer.append(new String(data, StandardCharsets.UTF_8));  // 添加为ASCII格式
                }

                if (transparentTimer != null) transparentTimer.cancel();  // 取消现有定时器
                transparentTimer = new Timer();      // 创建新定时器
                transparentTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        final String snapshot = transparentBuffer.toString().trim();  // 获取缓冲区快照
                        transparentBuffer.setLength(0);  // 清空缓冲区
                        if (!snapshot.isEmpty()) {    // 如果快照不为空
                            Platform.runLater(() -> {  // 在JavaFX应用线程中执行
                                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                                appendRecvText("[" + timestamp + " 接收]: " + snapshot + "\n");  // 显示接收数据
                            });
                        }
                    }
                }, 50);  // 50毫秒后执行
            }

            @Override
            public void onFrameReceived(byte[] frame) {  // 帧数据接收回调
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                StringBuilder displayBuilder = new StringBuilder();
                if (rbRecvHex.isSelected()) {       // 如果选择HEX显示
                    for (byte b : frame) displayBuilder.append(String.format("%02X ", b));  // 添加为HEX格式
                } else {                             // 如果选择ASCII显示
                    displayBuilder.append(new String(frame, StandardCharsets.UTF_8));  // 添加为ASCII格式
                }
                if (!displayBuilder.isEmpty()) {     // 如果数据不为空
                    Platform.runLater(() ->          // 在JavaFX应用线程中执行
                            appendRecvText("[" + timestamp + " 接收响应]: " + displayBuilder.toString().trim() + "\n\n")
                    );
                }
            }

            @Override
            public void onModbusCrcError() {         // CRC校验错误回调
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis());
                Platform.runLater(() ->              // 在JavaFX应用线程中执行
                        appendRecvText("[" + timestamp + " 接收异常]: CRC校验失败！\n\n")
                );
            }

            @Override
            public void onError(String message) {     // 错误信息回调
                Platform.runLater(() -> showAlert(AlertType.ERROR, "通信错误", message));
            }
        });
    }

    // ==========================================
    // 6. 工具方法
    // ==========================================
    private void appendRecvText(String text) {
        // 此方法通常已经在 Platform.runLater 中被调用，但做二次保护避免异常
        if (Platform.isFxApplicationThread()) {
            txtRecvArea.appendText(text);
        } else {
            Platform.runLater(() -> txtRecvArea.appendText(text));
        }
    }

    private void showAlert(AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
