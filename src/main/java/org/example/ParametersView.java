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
import java.nio.charset.StandardCharsets;
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
                if (rawHex == null) return;
                if (rawHex.contains("发送]:")) return;
                if (!rawHex.contains("接收]:")) return;

                // ★ 过滤 0x03 轮询响应
                String pureHex = rawHex.substring(rawHex.indexOf("]:") + 2)
                        .replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
                if (pureHex.length() >= 4 && "03".equals(pureHex.substring(2, 4))) {
                    return;
                }

                receivedData.append(rawHex).append("\n");

                String translated = translationManager.translate(rawHex);
                if (translated == null || translated.trim().isEmpty()) return;
                if (translated.startsWith("不支持")) return;
                if (translated.startsWith("数据格式错误")) return;
                if (translated.contains("无历史记录")) return;

                if (autoUpdate) {
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

                // 1. 构造上传报文（功能码 64 → 65）
                String[] newParts = new String[hexParts.length - 2];
                System.arraycopy(hexParts, 0, newParts, 0, 2);
                newParts[1] = "65";
                System.arraycopy(hexParts, 2, newParts, 2, hexParts.length - 4);

                // 2. ★ 把配置里的「设备地址」字段替换成当前地址
                Integer currentAddr = manager.getDeviceAddress();
                if (currentAddr != null) {
                    patchDeviceAddressField(newParts, currentAddr);
                }

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
    /**
     * 把上传报文里基础设置数据区的「设备地址」字段替换为当前地址。
     *
     * 报文结构：
     *   索引 0      : 从站地址
     *   索引 1      : 功能码（65）
     *   索引 2      : 数据长度
     *   索引 3      : 子包标记（01）
     *   索引 4      : 子包长度（38）
     *   索引 5 开始 : 基础设置数据区 w[0]
     *
     *   设备地址字段在数据区内偏移 32（H）和 33（L）
     *   → 报文整体索引 = 5 + 32 = 37、5 + 33 = 38
     *
     * @param hexParts   上传报文（每个元素是 1 字节的 hex 字符串，如 "01"）
     * @param currentAddr 当前设备地址（0~255）
     */
    private void patchDeviceAddressField(String[] hexParts, int currentAddr) {
        final int DATA_START   = 5;    // 基础设置数据区起点
        final int ADDR_OFFSET_H = 32;  // MENU_BASIC_AddrCodeH
        final int ADDR_OFFSET_L = 33;  // MENU_BASIC_AddrCodeL

        int hiIdx = DATA_START + ADDR_OFFSET_H;   // 37
        int loIdx = DATA_START + ADDR_OFFSET_L;   // 38

        if (loIdx >= hexParts.length) {
            data.add(new ConfigParam("警告",
                    "报文长度不足，未找到设备地址字段（需要至少 " + (loIdx + 1) + " 字节）", ""));
            return;
        }

        // ★ 高位固定为 0（按宏注释「高位无用 始终为0」）
        hexParts[hiIdx] = "00";
        hexParts[loIdx] = String.format("%02X", currentAddr & 0xFF);

        data.add(new ConfigParam("系统",
                String.format("已将配置中的设备地址字段替换为: %d (报文索引 %d~%d)",
                        currentAddr, hiIdx, loIdx), ""));
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

        FileChooser csvChooser = new FileChooser();
        csvChooser.setTitle("保存配置文件");
        csvChooser.setInitialFileName("config_" + timestamp + ".csv");
        csvChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV 文件 (*.csv)", "*.csv"),
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
        File csvFile = csvChooser.showSaveDialog(getWindow());

        if (csvFile != null) {
            try {
                StringBuilder content = new StringBuilder();
                content.append("类别,参数名,值\n");

                for (ConfigParam param : data) {
                    if (!param.getCategory().equals("系统") &&
                            !param.getCategory().equals("错误") &&
                            !param.getCategory().equals("提示")) {
                        content.append(escapeCsv(param.getCategory())).append(",")
                                .append(escapeCsv(param.getName())).append(",")
                                .append(escapeCsv(param.getValue())).append("\n");
                    }
                }

                byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                byte[] body = content.toString().getBytes(StandardCharsets.UTF_8);
                byte[] out = new byte[bom.length + body.length];
                System.arraycopy(bom, 0, out, 0, bom.length);
                System.arraycopy(body, 0, out, bom.length, body.length);

                Files.write(csvFile.toPath(), out);

                // 保存 cfg（原始 hex）
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
                        Files.write(cfgFile.toPath(),
                                formattedHex.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                data.add(new ConfigParam("错误", "文件保存失败: " + e.getMessage(), ""));
            }
        }
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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