package org.example;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 配置查看页面控制器
 *
 * 修复要点：
 *  1. onRawData 只记录，不做帧级过滤（串口是按分包回调的，无法对单包判断）
 *  2. onTranslatedData 直接 appendText，不再依赖 receivedData 是否非空
 *  3. 不再用 startsWith("01 64") 判断（设备地址可能是 02/0A/...）
 *  4. 带 PAGE_TAG 注册监听器和发送，隔离其他页面的回包
 *  5. 页面销毁时移除监听器，防止累积
 */
public class ParametersView {

    // ★ 页面唯一标识
    private static final String PAGE_TAG = "ParametersView";

    // ============ FXML 注入 ============
    @FXML private TextArea txtParameters;
    @FXML private TextArea txtParameters1;

    // ============ 内部字段 ============
    private String pendingData = null;
    private SerialManager manager;
    private StringBuilder receivedData = new StringBuilder();
    private boolean autoUpdate = true;

    // ★ 保存监听器引用，便于页面销毁时移除
    private SerialManager.SerialEventListener myListener;

    // ============================================================
    //  初始化
    // ============================================================
    @FXML
    public void initialize() {
        manager = SerialManager.getInstance();

        myListener = new SerialManager.SerialEventListener() {

            @Override
            public void onRawData(String rawHex) {
                // ★ 只做记录，不做过滤（底层按分包回调，无法对单包判断功能码）
                // 想调试时打开下面这行：
                // System.out.println("[配置页 RAW] " + rawHex);
                receivedData.append(rawHex).append("\n");
            }

            @Override
            public void onTranslatedData(String translatedText) {
                // ★ 能进入这里说明 SerialManager 已经拼完完整帧并翻译完成
                // 不需要再做任何过滤（tag 机制已保证是本页面的回包）
                // System.out.println("[配置页 TR] " + translatedText); // 调试用
                Platform.runLater(() -> {
                    if (autoUpdate
                            && translatedText != null
                            && !translatedText.trim().isEmpty()) {
                        txtParameters.appendText(translatedText + "\n");
                    }
                });
            }

            @Override
            public void onSystemLog(String log) {
                Platform.runLater(() ->
                        txtParameters.appendText("[系统] " + log + "\n"));
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() ->
                        txtParameters.appendText("[错误] " + error + "\n"));
            }
        };

        // ★ 带 PAGE_TAG 注册，只接收本页面发出的指令对应的回包
        manager.addListener(PAGE_TAG, myListener);

        // 延迟发送初始指令，确保界面完全加载
        Platform.runLater(() -> {
            try {
                sendInitialCommand();
            } catch (Exception e) {
                txtParameters.appendText("[错误] 发送初始指令失败: " + e.getMessage() + "\n");
            }
        });
    }

    // ============================================================
    //  页面销毁：移除监听器，防止累积和重复刷新
    // ============================================================
    public void onDestroy() {
        if (myListener != null && manager != null) {
            manager.removeListener(myListener);
            myListener = null;
        }
    }

    // ============================================================
    //  发送初始指令
    // ============================================================
    private void sendInitialCommand() {
        // 首字节使用当前设备地址拼（SerialManager 内部还会再替换一次，结果一致）
        Integer addr = manager.getDeviceAddress();
        String addrHex = (addr == null) ? "01" : String.format("%02X", addr);
        String initialCommand = addrHex + " 64 00 00 00 00";

        try {
            txtParameters.clear();
            receivedData.setLength(0);
            sendHexCommand(initialCommand);
        } catch (Exception e) {
            txtParameters.appendText("[错误] 发送初始指令失败: " + e.getMessage() + "\n");
        }
    }

    // ============================================================
    //  按钮事件
    // ============================================================
    @FXML
    private void handleRefresh() {
        autoUpdate = true;
        receivedData.setLength(0);
        txtParameters.clear();
        sendInitialCommand();
    }

    @FXML
    private void handleClear() {
        txtParameters.clear();
        txtParameters1.clear();
    }

    @FXML
    private void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择配置文件");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg"));
        File selectedFile = fileChooser.showOpenDialog(getWindow());

        if (selectedFile != null) {
            try {
                String content = new String(
                        Files.readAllBytes(Paths.get(selectedFile.getAbsolutePath())));
                String[] hexParts = content.trim().split("\\s+");

                if (hexParts.length < 4) {
                    txtParameters.appendText("[错误] 配置文件格式无效\n");
                    return;
                }

                // 构造新数组：前两字节 + 剩余（去掉最后两字节 CRC）
                String[] newParts = new String[hexParts.length - 2];
                System.arraycopy(hexParts, 0, newParts, 0, 2);
                newParts[1] = "65";   // 修改功能码为 65
                System.arraycopy(hexParts, 2, newParts, 2, hexParts.length - 4);

                pendingData = String.join(" ", newParts);
                txtParameters1.setText(pendingData);
                txtParameters.appendText("[系统] 配置文件已加载，点击发送按钮发送数据\n");

            } catch (IOException e) {
                txtParameters.appendText("[错误] 文件读取失败: " + e.getMessage() + "\n");
            } catch (Exception e) {
                txtParameters.appendText("[错误] 数据处理失败: " + e.getMessage() + "\n");
            }
        }
    }

    @FXML
    private void handleSend() {
        if (pendingData == null) {
            txtParameters.appendText("[提示] 请先上传配置文件\n");
            return;
        }
        if (!manager.isPortOpen()) {
            txtParameters.appendText("[提示] 串口未打开，无法发送！\n");
            return;
        }

        try {
            String[] hexParts = pendingData.trim().split("\\s+");
            byte[] dataToSend = new byte[hexParts.length];

            for (int i = 0; i < hexParts.length; i++) {
                int high = Character.digit(hexParts[i].charAt(0), 16);
                int low  = Character.digit(hexParts[i].charAt(1), 16);
                if (high == -1 || low == -1) {
                    txtParameters.appendText("[错误] 包含非HEX字符！\n");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            // ★ 带 PAGE_TAG 发送
            manager.sendData(dataToSend, PAGE_TAG);
            txtParameters.appendText("[系统] 数据已发送\n");

        } catch (Exception e) {
            txtParameters.appendText("[错误] 发送失败: " + e.getMessage() + "\n");
        }
    }

    @FXML
    private void handleDownload() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // 1. 先保存翻译后的文本文件
        FileChooser txtChooser = new FileChooser();
        txtChooser.setTitle("保存配置文件");
        txtChooser.setInitialFileName("config_" + timestamp + ".txt");
        txtChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
        File txtFile = txtChooser.showSaveDialog(getWindow());

        if (txtFile != null) {
            try {
                Files.write(txtFile.toPath(), txtParameters.getText().getBytes());

                // 2. 再保存十六进制数据文件
                FileChooser cfgChooser = new FileChooser();
                cfgChooser.setTitle("保存原始数据");
                cfgChooser.setInitialFileName("config_" + timestamp + ".cfg");
                cfgChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg"),
                        new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
                File cfgFile = cfgChooser.showSaveDialog(getWindow());

                if (cfgFile != null) {
                    String hexData = DeviceProtocolTranslator.getFullHexData();
                    if (hexData != null && !hexData.isEmpty()) {
                        // 每两个字符加一个空格
                        StringBuilder formattedHex = new StringBuilder();
                        for (int i = 0; i < hexData.length(); i += 2) {
                            if (i > 0) formattedHex.append(" ");
                            formattedHex.append(hexData.substring(i,
                                    Math.min(i + 2, hexData.length())));
                        }
                        Files.write(cfgFile.toPath(), formattedHex.toString().getBytes());
                        txtParameters.appendText("[系统] 配置文件和可浏览文本已保存\n");
                    } else {
                        txtParameters.appendText("[警告] 没有可用的配置文件\n");
                    }
                }
            } catch (IOException e) {
                txtParameters.appendText("[错误] 文件保存失败: " + e.getMessage() + "\n");
            }
        }
    }

    // ============================================================
    //  HEX 字符串发送工具（带 tag）
    // ============================================================
    private void sendHexCommand(String hexStr) {
        if (hexStr == null || hexStr.trim().isEmpty()) return;

        try {
            String cleanHex = hexStr.replace(" ", "").replace("\n", "").replace("\r", "");
            if (cleanHex.length() % 2 != 0) {
                txtParameters.appendText("[错误] HEX格式错误，长度必须为偶数！\n");
                return;
            }

            byte[] dataToSend = new byte[cleanHex.length() / 2];
            for (int i = 0; i < dataToSend.length; i++) {
                int high = Character.digit(cleanHex.charAt(i * 2), 16);
                int low  = Character.digit(cleanHex.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) {
                    txtParameters.appendText("[错误] 包含非HEX字符，请检查输入！\n");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            manager.setCrcEnabled(true);
            // ★ 带 PAGE_TAG 发送，回包只发给本页面
            manager.sendData(dataToSend, PAGE_TAG);

        } catch (Exception e) {
            txtParameters.appendText("[错误] 发送异常: " + e.getMessage() + "\n");
        }
    }

    // ============================================================
    //  工具方法
    // ============================================================
    private Window getWindow() {
        return txtParameters.getScene().getWindow();
    }
}