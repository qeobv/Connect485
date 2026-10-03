package org.example;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

public class ParametersView {
    private static final String PAGE_TAG = "ParametersView";

    @FXML private TableView<ConfigParam> configTable;
    @FXML private TableColumn<ConfigParam, String> categoryColumn;
    @FXML private TableColumn<ConfigParam, String> nameColumn;
    @FXML private TableColumn<ConfigParam, String> valueColumn;
    @FXML private TextArea txtParameters1;

    private SerialManager manager;
    private SerialManager.SerialEventListener myListener;
    private final ProtocolTranslationManager translationManager =
            ProtocolTranslationManager.getInstance();

    private ObservableList<ConfigParam> data = FXCollections.observableArrayList();
    private String pendingData = null;
    private StringBuilder receivedData = new StringBuilder();
    private boolean autoUpdate = true;

    public static class ConfigParam {
        private String category;
        private String name;
        private String value;

        public ConfigParam(String category, String name, String value) {
            this.category = category;
            this.name = name;
            this.value = value;
        }

        public String getCategory() { return category; }
        public String getName() { return name; }
        public String getValue() { return value; }
    }

    @FXML
    public void initialize() {
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        valueColumn.setCellValueFactory(new PropertyValueFactory<>("value"));
        configTable.setItems(data);

        manager = SerialManager.getInstance();
        setupSerialListener();

        Platform.runLater(this::sendInitialCommand);
    }

    private void setupSerialListener() {
        myListener = new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                receivedData.append(rawHex).append("\n");

                // ★ 自己翻译，过滤掉不支持的
                String translated = translationManager.translate(rawHex);
                if (autoUpdate && translated != null
                        && !translated.trim().isEmpty()
                        && !translated.startsWith("不支持")
                        && !translated.startsWith("数据格式错误")) {
                    Platform.runLater(() -> updateConfigTable(translated));
                }
            }

            @Override
            public void onTranslatedData(String translatedText) {
                // SerialManager 不再主动触发
            }

            @Override
            public void onSystemLog(String log) {
                Platform.runLater(() -> {
                    data.add(new ConfigParam("系统", log, ""));
                    configTable.scrollTo(data.size() - 1);
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> {
                    data.add(new ConfigParam("错误", error, ""));
                    configTable.scrollTo(data.size() - 1);
                });
            }
        };

        manager.addListener(PAGE_TAG, myListener);
    }

    private void updateConfigTable(String translatedText) {
        if (translatedText == null || translatedText.trim().isEmpty()) return;

        data.clear();

        String[] lines = translatedText.split("\n");
        String currentCategory = "";

        for (String line : lines) {
            if (line.startsWith("======")) {
                currentCategory = line.replace("======", "").trim();
                continue;
            }

            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                String name = parts[0].trim();
                String value = parts[1].trim();
                data.add(new ConfigParam(currentCategory, name, value));
            }
        }

        configTable.scrollTo(data.size() - 1);
    }

    private void sendInitialCommand() {
        Integer addr = manager.getDeviceAddress();
        String addrHex = (addr == null) ? "01" : String.format("%02X", addr);
        String initialCommand = addrHex + " 64 00 00 00 00";

        try {
            data.clear();
            receivedData.setLength(0);
            sendHexCommand(initialCommand);
        } catch (Exception e) {
            data.add(new ConfigParam("错误", "发送初始指令失败: " + e.getMessage(), ""));
        }
    }

    @FXML
    private void handleRefresh() {
        autoUpdate = true;
        receivedData.setLength(0);
        sendInitialCommand();
    }

    @FXML
    private void handleClear() {
        data.clear();
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
                    data.add(new ConfigParam("错误", "配置文件格式无效", ""));
                    return;
                }

                String[] newParts = new String[hexParts.length - 2];
                System.arraycopy(hexParts, 0, newParts, 0, 2);
                newParts[1] = "65";
                System.arraycopy(hexParts, 2, newParts, 2, hexParts.length - 4);

                pendingData = String.join(" ", newParts);
                txtParameters1.setText(pendingData);
                data.add(new ConfigParam("系统", "配置文件已加载，点击发送按钮发送数据", ""));

            } catch (IOException e) {
                data.add(new ConfigParam("错误", "文件读取失败: " + e.getMessage(), ""));
            } catch (Exception e) {
                data.add(new ConfigParam("错误", "数据处理失败: " + e.getMessage(), ""));
            }
        }
    }

    @FXML
    private void handleSend() {
        if (pendingData == null) {
            data.add(new ConfigParam("提示", "请先上传配置文件", ""));
            return;
        }
        if (!manager.isPortOpen()) {
            data.add(new ConfigParam("提示", "串口未打开，无法发送！", ""));
            return;
        }

        try {
            Integer currentAddr = manager.getDeviceAddress();
            if (currentAddr == null) {
                data.add(new ConfigParam("错误", "未设置设备地址！", ""));
                return;
            }

            String[] hexParts = pendingData.trim().split("\\s+");
            byte[] dataToSend = new byte[hexParts.length];

            dataToSend[0] = (byte) (currentAddr & 0xFF);

            for (int i = 1; i < hexParts.length; i++) {
                int high = Character.digit(hexParts[i].charAt(0), 16);
                int low  = Character.digit(hexParts[i].charAt(1), 16);
                if (high == -1 || low == -1) {
                    data.add(new ConfigParam("错误", "包含非HEX字符！", ""));
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            manager.sendData(dataToSend, PAGE_TAG);
            data.add(new ConfigParam("系统", "数据已发送，使用设备地址: " + currentAddr, ""));

        } catch (Exception e) {
            data.add(new ConfigParam("错误", "发送失败: " + e.getMessage(), ""));
        }
    }

    @FXML
    private void handleDownload() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        FileChooser txtChooser = new FileChooser();
        txtChooser.setTitle("保存配置文件");
        txtChooser.setInitialFileName("config_" + timestamp + ".txt");
        txtChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
        File txtFile = txtChooser.showSaveDialog(getWindow());

        if (txtFile != null) {
            try {
                StringBuilder content = new StringBuilder();
                for (ConfigParam param : data) {
                    if (!param.getCategory().equals("系统") &&
                            !param.getCategory().equals("错误") &&
                            !param.getCategory().equals("提示")) {
                        content.append(param.getName()).append(": ").append(param.getValue()).append("\n");
                    }
                }

                Files.write(txtFile.toPath(), content.toString().getBytes());

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
                        StringBuilder formattedHex = new StringBuilder();
                        for (int i = 0; i < hexData.length(); i += 2) {
                            if (i > 0) formattedHex.append(" ");
                            formattedHex.append(hexData.substring(i,
                                    Math.min(i + 2, hexData.length())));
                        }
                        Files.write(cfgFile.toPath(), formattedHex.toString().getBytes());
                    }
                }
            } catch (IOException e) {
                data.add(new ConfigParam("错误", "文件保存失败: " + e.getMessage(), ""));
            }
        }
    }

    private void sendHexCommand(String hexStr) {
        if (hexStr == null || hexStr.trim().isEmpty()) return;

        try {
            String cleanHex = hexStr.replace(" ", "").replace("\n", "").replace("\r", "");
            if (cleanHex.length() % 2 != 0) {
                data.add(new ConfigParam("错误", "HEX格式错误，长度必须为偶数！", ""));
                return;
            }

            byte[] dataToSend = new byte[cleanHex.length() / 2];
            for (int i = 0; i < dataToSend.length; i++) {
                int high = Character.digit(cleanHex.charAt(i * 2), 16);
                int low  = Character.digit(cleanHex.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) {
                    data.add(new ConfigParam("错误", "包含非HEX字符，请检查输入！", ""));
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            manager.setCrcEnabled(true);
            manager.sendData(dataToSend, PAGE_TAG);

        } catch (Exception e) {
            data.add(new ConfigParam("错误", "发送异常: " + e.getMessage(), ""));
        }
    }

    private Window getWindow() {
        return configTable.getScene().getWindow();
    }

    public void onDestroy() {
        if (myListener != null && manager != null) {
            manager.removeListener(myListener);
            myListener = null;
        }
    }
}