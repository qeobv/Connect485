package org.example;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Window;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ResourceBundle;

/**
 * 调试界面控制器
 * 职责：底层原始报文监视、手动发送指令、CRC校验控制、报文导入
 */
public class DebugViewController implements Initializable {

    // ==========================================
    // 1. 控件注入 (与 DebugView.fxml 中的 fx:id 一一对应)
    // ==========================================
    @FXML private TextArea txtDebugRecv;      // 原始报文接收区
    @FXML private TextArea txtSendData;       // 报文发送区
    @FXML private RadioButton rbSendHex;      // HEX发送单选按钮
    @FXML private CheckBox chkEnableCrc;      // 启用CRC校验复选框
    @FXML private Button btnImportData;       // 导入报文按钮
    @FXML private Button btnSend;             // 发送按钮
    @FXML private Button btnClearDebugRecv;   // 清空接收区按钮

    // ==========================================
    // 2. 核心引擎引用
    // ==========================================
    private SerialManager manager;

    // ==========================================
    // 3. 初始化
    // ==========================================
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        manager = SerialManager.getInstance(); // 获取全局单例引擎

        // 默认勾选 CRC，与底层引擎同步状态
        chkEnableCrc.setSelected(manager.isPortOpen());
        // 更好的做法是读取 SerialManager 的当前状态，这里默认给 true
        chkEnableCrc.setSelected(true);

        // 注册调试界面专属的监听器
        setupListeners();
    }

    private void setupListeners() {
        manager.addListener(new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                // 调试界面核心：实时接收并显示原始 Hex 报文
                Platform.runLater(() -> txtDebugRecv.appendText(rawHex + "\n"));
            }

            @Override
            public void onTranslatedData(String translatedText) {
                // 调试界面忽略翻译后的业务数据
            }

            @Override
            public void onSystemLog(String log) {
                // 显示系统日志，方便调试人员知晓串口状态变化
                Platform.runLater(() -> txtDebugRecv.appendText("[系统] " + log + "\n"));
            }

            @Override
            public void onError(String error) {
                // 显示错误信息 (如 CRC 校验失败等)
                Platform.runLater(() -> txtDebugRecv.appendText("[错误] " + error + "\n"));
            }
        });
    }

    // ==========================================
    // 4. 发送数据逻辑
    // ==========================================
    @FXML
    private void sendData() {
        if (!manager.isPortOpen()) {
            txtDebugRecv.appendText("[提示] 串口未打开，无法发送！\n");
            return;
        }

        String input = txtSendData.getText().trim();
        if (input.isEmpty()) return;

        try {
            byte[] dataToSend;

            if (rbSendHex.isSelected()) {
                // HEX 格式发送：去除所有空格和换行
                String hexStr = input.replace(" ", "").replace("\n", "").replace("\r", "");
                if (hexStr.length() % 2 != 0) {
                    txtDebugRecv.appendText("[错误] HEX格式错误，长度必须为偶数！\n");
                    return;
                }

                dataToSend = new byte[hexStr.length() / 2];
                for (int i = 0; i < dataToSend.length; i++) {
                    int high = Character.digit(hexStr.charAt(i * 2), 16);
                    int low = Character.digit(hexStr.charAt(i * 2 + 1), 16);
                    if (high == -1 || low == -1) {
                        txtDebugRecv.appendText("[错误] 包含非HEX字符！\n");
                        return;
                    }
                    dataToSend[i] = (byte) ((high << 4) | low);
                }
            } else {
                // ASCII 格式发送
                dataToSend = input.getBytes(StandardCharsets.UTF_8);
            }

            // 同步 CRC 开关状态给核心引擎
            manager.setCrcEnabled(chkEnableCrc.isSelected());

            // 调用核心引擎统一发送 (引擎内部会处理CRC追加和日志广播)
            manager.sendData(dataToSend);

        } catch (Exception e) {
            txtDebugRecv.appendText("[异常] 发送失败: " + e.getMessage() + "\n");
        }
    }

    // ==========================================
    // 5. 辅助操作逻辑 (清空、导入)
    // ==========================================
    @FXML
    private void clearDebugRecv() {
        // 调用核心引擎的统一清空工具
        manager.clearTextAreas(txtDebugRecv);
    }

    @FXML
    private void importTxtData() {
        // 获取窗口用于挂载文件选择器
        Window stage = btnImportData.getScene().getWindow();

        // 调用核心引擎的统一导入工具
        String content = manager.importTextData(stage);

        if (content != null) {
            txtSendData.setText(content);
        }
    }
}
