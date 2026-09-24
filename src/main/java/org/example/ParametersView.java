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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParametersView {
    @FXML
    private TextArea txtParameters;
    @FXML
    private TextArea txtParameters1;

    private String pendingData = null;
    private SerialManager manager;
    private StringBuilder receivedData = new StringBuilder();
    private boolean autoUpdate = true;  // 修改为 true，默认开启自动更新

    @FXML
    public void initialize() {
        manager = SerialManager.getInstance();

        // 配置数据过滤器
        manager.setDataFilter(data -> {
            // 只处理配置相关的数据
            return data.startsWith("01 64") || data.startsWith("01 65");
        });

        manager.addListener(new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                // 只存储配置相关的数据
                if (rawHex.startsWith("01 64") || rawHex.startsWith("01 65")) {
                    receivedData.append(rawHex).append("\n");
                }
            }

            @Override
            public void onTranslatedData(String translatedText) {
                Platform.runLater(() -> {
                    // 只在 autoUpdate 为 true 且数据是配置相关时更新
                    if (autoUpdate && (receivedData.length() > 0)) {
                        txtParameters.setText(translatedText);
                    }
                });
            }

            @Override
            public void onSystemLog(String log) {
                Platform.runLater(() -> {
                    txtParameters.appendText("[系统] " + log + "\n");
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> {
                    txtParameters.appendText("[错误] " + error + "\n");
                });
            }
        });

        // 延迟发送初始指令，确保界面完全加载
        Platform.runLater(() -> {
            try {
                sendInitialCommand();
            } catch (Exception e) {
                txtParameters.appendText("[错误] 发送初始指令失败: " + e.getMessage() + "\n");
            }
        });
    }

    private boolean isValidConfigData(String data) {
        // 检查数据格式
        if (!data.matches("01 6[45][0-9A-Fa-f ]*")) {
            return false;
        }
        // 检查数据长度
        String cleanData = data.replace(" ", "");
        return cleanData.length() >= 6; // 最少6个字符（3个字节）
    }

    private void sendInitialCommand() {
        String initialCommand = "01 64 00 00 00 00";
        try {
            txtParameters.clear();
            sendHexCommand(initialCommand);
        } catch (Exception e) {
            txtParameters.appendText("[错误] 发送初始指令失败: " + e.getMessage() + "\n");
        }
    }

    @FXML
    private void handleRefresh() {
        // 开启自动更新
        autoUpdate = true;
        // 清空已接收的数据
        receivedData.setLength(0);
        // 发送初始指令前先清空显示
        txtParameters.clear();
        sendInitialCommand();
    }


    @FXML
    private void handleClear() {
        txtParameters.clear();
    }

    @FXML
    private void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择配置文件");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg")
        );
        File selectedFile = fileChooser.showOpenDialog(getWindow());

        if (selectedFile != null) {
            try {
                // 读取文件内容
                String content = new String(Files.readAllBytes(Paths.get(selectedFile.getAbsolutePath())));

                // 按空格分割成字符串数组
                String[] hexParts = content.trim().split("\\s+");

                // 验证数据有效性
                if (hexParts.length < 4) {
                    txtParameters.appendText("[错误] 配置文件格式无效\n");
                    return;
                }

                // 创建新的字符串数组
                String[] newParts = new String[hexParts.length - 2];

                // 复制并修改功能码
                System.arraycopy(hexParts, 0, newParts, 0, 2); // 复制前两个字节
                newParts[1] = "65"; // 修改功能码

                // 复制剩余数据（除了最后两位CRC）
                System.arraycopy(hexParts, 2, newParts, 2, hexParts.length - 4);

                // 重新组合成带空格的字符串
                pendingData = String.join(" ", newParts);

                // 只显示在文本框中，不发送
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
            // 处理十六进制数据
            String[] hexParts = pendingData.trim().split("\\s+");
            byte[] dataToSend = new byte[hexParts.length];

            for (int i = 0; i < hexParts.length; i++) {
                int high = Character.digit(hexParts[i].charAt(0), 16);
                int low = Character.digit(hexParts[i].charAt(1), 16);
                if (high == -1 || low == -1) {
                    txtParameters.appendText("[错误] 包含非HEX字符！\n");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            // 发送数据
            manager.sendData(dataToSend);
            txtParameters.appendText("[系统] 数据已发送\n");

        } catch (Exception e) {
            txtParameters.appendText("[错误] 发送失败: " + e.getMessage() + "\n");
        }
    }



    @FXML
    private void handleDownload() {
        // 生成带时间戳的基础文件名
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // 1. 先保存翻译后的文本文件
        FileChooser txtChooser = new FileChooser();
        txtChooser.setTitle("保存配置文件");
        txtChooser.setInitialFileName("config_" + timestamp + ".txt");
        txtChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*")
        );
        File txtFile = txtChooser.showSaveDialog(getWindow());

        if (txtFile != null) {
            try {
                // 保存翻译后的文本
                Files.write(txtFile.toPath(), txtParameters.getText().getBytes());

                // 2. 再保存十六进制数据文件
                FileChooser cfgChooser = new FileChooser();
                cfgChooser.setTitle("保存原始数据");
                cfgChooser.setInitialFileName("config_" + timestamp + ".cfg");
                cfgChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg"),
                        new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*")
                );
                File cfgFile = cfgChooser.showSaveDialog(getWindow());

                if (cfgFile != null) {
                    // 直接使用存储的完整十六进制数据
                    String hexData = DeviceProtocolTranslator.getFullHexData();
                    if (hexData != null && !hexData.isEmpty()) {
                        // 添加格式化，每两个字符加一个空格
                        StringBuilder formattedHex = new StringBuilder();
                        for (int i = 0; i < hexData.length(); i += 2) {
                            if (i > 0) formattedHex.append(" ");
                            formattedHex.append(hexData.substring(i, Math.min(i + 2, hexData.length())));
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

    private void sendHexCommand(String hexStr) {
        if (hexStr == null || hexStr.trim().isEmpty()) {
            return;
        }
        try {
            String cleanHex = hexStr.replace(" ", "").replace("\n", "").replace("\r", "");
            if (cleanHex.length() % 2 != 0) {
                txtParameters.appendText("[错误] HEX格式错误，长度必须为偶数！\n");
                return;
            }

            byte[] dataToSend = new byte[cleanHex.length() / 2];
            for (int i = 0; i < dataToSend.length; i++) {
                int high = Character.digit(cleanHex.charAt(i * 2), 16);
                int low = Character.digit(cleanHex.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) {
                    txtParameters.appendText("[错误] 包含非HEX字符，请检查输入！\n");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            manager.setCrcEnabled(true);
            manager.sendData(dataToSend);
        } catch (Exception e) {
            txtParameters.appendText("[错误] 发送异常: " + e.getMessage() + "\n");
        }
    }

    private Window getWindow() {
        return txtParameters.getScene().getWindow();
    }
}
