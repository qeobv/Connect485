package org.example;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.core.ConfigEdit;
import org.example.core.ConfigFieldDef;
import org.example.core.ConfigFieldValue;
import org.example.core.ConfigModel;

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

    /** 待写入设备的字段编辑（脏数据） */
    private final List<ConfigEdit> edits = new ArrayList<>();

    public static class ConfigParam {
        private final String category;
        private final String name;
        private String value;
        private final ConfigFieldDef field;   // null = 系统/错误消息行
        private boolean dirty;

        public ConfigParam(String category, String name, String value) {
            this(category, name, value, null);
        }

        public ConfigParam(String category, String name, String value, ConfigFieldDef field) {
            this.category = category;
            this.name = name;
            this.value = value;
            this.field = field;
            this.dirty = false;
        }

        public String getCategory() { return category; }
        public String getName() { return name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public ConfigFieldDef getField() { return field; }
        public boolean isDirty() { return dirty; }
        public void setDirty(boolean dirty) { this.dirty = dirty; }
    }

    @FXML
    public void initialize() {
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        valueColumn.setCellValueFactory(new PropertyValueFactory<>("value"));
        // 脏行高亮（修改后待写入的字段）
        valueColumn.setCellFactory(col -> new TableCell<ConfigParam, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                ConfigParam p = getTableRow().getItem();
                setText(item);
                setStyle(p.isDirty() ? "-fx-background-color: #FEF3C7; -fx-font-weight: bold;" : "");
            }
        });
        configTable.setItems(data);

        // 双击行编辑
        configTable.setRowFactory(tv -> {
            TableRow<ConfigParam> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    editParam(row.getItem());
                }
            });
            return row;
        });

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

                // 优先结构化解析（0x64 配置帧 → 可编辑表格）
                ConfigModel model = manager.getProfile().getConfigModel();
                List<ConfigFieldValue> fieldValues = null;
                if (model != null) {
                    try {
                        fieldValues = model.decode(hexToBytes(pureHex));
                    } catch (Exception ignored) { }
                }
                if (fieldValues != null) {
                    if (autoUpdate) {
                        List<ConfigFieldValue> captured = fieldValues;
                        Platform.runLater(() -> updateTableFromModel(captured));
                    }
                    return;
                }

                String translated = manager.getProfile().translate(rawHex);
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

    /** 结构化配置帧 → 表格（可编辑行携带字段定义） */
    private void updateTableFromModel(List<ConfigFieldValue> values) {
        if (values == null) return;
        data.clear();
        for (ConfigFieldValue v : values) {
            data.add(new ConfigParam(v.def.category, v.def.name, v.displayText, v.def));
        }
        configTable.scrollTo(0);
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    // ==========================================
    //  单参数编辑
    // ==========================================
    private void editParam(ConfigParam param) {
        if (param == null) return;
        ConfigFieldDef field = param.getField();
        if (field == null || !field.editable) {
            showInfo("该参数不可修改");
            return;
        }
        ConfigModel model = manager.getProfile().getConfigModel();
        if (model == null) return;

        // ENUM 与 BIT（如保护投退的开启/关闭）都带枚举映射，弹下拉选择框
        if (field.type == ConfigFieldDef.FieldType.ENUM
                || field.type == ConfigFieldDef.FieldType.BIT) {
            List<String> options = new ArrayList<>(field.enumMap.values());
            ChoiceDialog<String> dialog = new ChoiceDialog<>(param.getValue(), options);
            dialog.setTitle("修改参数");
            dialog.setHeaderText(field.name);
            dialog.setContentText("选择新值:");
            dialog.showAndWait().ifPresent(sel -> applyFieldEdit(param, field, model, sel));
        } else {
            TextInputDialog dialog = new TextInputDialog(param.getValue());
            dialog.setTitle("修改参数");
            dialog.setHeaderText(field.name + (field.unit != null ? "（单位: " + field.unit + "）" : ""));
            dialog.setContentText(buildPrompt(field));
            dialog.showAndWait().ifPresent(input -> applyFieldEdit(param, field, model, input));
        }
    }

    private void applyFieldEdit(ConfigParam param, ConfigFieldDef field, ConfigModel model, String input) {
        ConfigEdit edit = model.encodeField(field, input);
        if (edit == null) {
            showInfo("输入无效: " + input);
            return;
        }
        param.setValue(edit.displayText);
        param.setDirty(true);
        edits.add(edit);
        configTable.refresh();
        data.add(new ConfigParam("系统", "参数「" + field.name + "」已修改，点击【写入设备】统一下发", null));
        configTable.scrollTo(data.size() - 1);
    }

    private String buildPrompt(ConfigFieldDef field) {
        return switch (field.type) {
            case BYTE -> "输入 0~255 的整数:";
            case BYTES2 -> "输入 0~65535 的整数:";
            case SCALED_BYTE -> "输入小数（如 0.5），最多一位小数:";
            case SCALED_BYTES2 -> "输入小数（如 50.5），最多一位小数:";
            case TIME_HM -> "输入格式 HH:MM（如 08:30）:";
            case POS4096 -> "输入格式 商.余数（如 2047.3488，余数 < 4096）:";
            default -> "输入新值:";
        };
    }

    @FXML
    private void handleApplyEdits() {
        if (edits.isEmpty()) {
            showInfo("没有待写入的修改");
            return;
        }
        if (!manager.isPortOpen()) {
            showInfo("串口未打开，无法写入！");
            return;
        }
        ConfigModel model = manager.getProfile().getConfigModel();
        if (model == null) return;
        byte[] lastFrame = model.getLastConfigFrame();
        if (lastFrame == null) {
            showInfo("尚未读到配置帧，请先点击【刷新】");
            return;
        }

        try {
            byte[] writeFrame = model.applyEdits(lastFrame, edits);
            if (writeFrame == null) {
                showInfo("生成写配置帧失败");
                return;
            }
            int count = edits.size();
            edits.clear();
            manager.setCrcEnabled(true);   // 整包 CRC 由 SerialManager 统一追加
            manager.sendData(writeFrame, PAGE_TAG);
            data.add(new ConfigParam("系统", "已下发 " + count + " 项修改，正在重新读取配置...", null));
            configTable.scrollTo(data.size() - 1);

            // 延迟重读：0x65 写配置后设备可能复位重启，多留时间（3 秒）
            PauseTransition delay = new PauseTransition(Duration.millis(3000));
            delay.setOnFinished(e -> sendInitialCommand());
            delay.play();
        } catch (Exception e) {
            data.add(new ConfigParam("错误", "写入失败: " + e.getMessage(), null));
        }
    }

    @FXML
    private void handleDiscardEdits() {
        edits.clear();
        sendInitialCommand();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void sendInitialCommand() {
        Integer addr = manager.getDeviceAddress();
        if (addr == null) addr = 1;

        try {
            data.clear();
            receivedData.setLength(0);

            byte[] cmd = manager.getProfile().buildReadConfigCommand(addr);
            if (cmd == null) {
                data.add(new ConfigParam("错误", "当前板卡系列不支持配置读取", ""));
                return;
            }
            manager.setCrcEnabled(true);
            manager.sendData(cmd, PAGE_TAG);
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

            // 上传的帧子包 CRC 可能因文件被手工修改而失效，
            // 发送前由当前系列的配置模型重算三个子包 CRC（整包 CRC 由 SerialManager 追加）。
            ConfigModel model = manager.getProfile().getConfigModel();
            if (model != null) {
                byte[] fullFrame = new byte[dataToSend.length + 2];
                System.arraycopy(dataToSend, 0, fullFrame, 0, dataToSend.length);
                byte[] recalc = model.applyEdits(fullFrame, java.util.Collections.emptyList());
                if (recalc != null) {
                    dataToSend = recalc;
                }
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
        // 文件名前缀带系列标识（如 g3_config_... / mta_config_...）
        String series = manager.getProfile().id();

        FileChooser csvChooser = new FileChooser();
        csvChooser.setTitle("保存配置文件");
        csvChooser.setInitialFileName(series + "_config_" + timestamp + ".csv");
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
                cfgChooser.setInitialFileName(series + "_config_" + timestamp + ".cfg");
                cfgChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("配置文件 (*.cfg)", "*.cfg"),
                        new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
                File cfgFile = cfgChooser.showSaveDialog(getWindow());

                if (cfgFile != null) {
                    // 原始帧来自当前系列的配置模型（decode 时保存的完整帧），
                    // 不再依赖调试窗口翻译路径的静态缓存。
                    ConfigModel model = manager.getProfile().getConfigModel();
                    byte[] lastFrame = model != null ? model.getLastConfigFrame() : null;
                    if (lastFrame != null && lastFrame.length > 0) {
                        StringBuilder formattedHex = new StringBuilder();
                        for (int i = 0; i < lastFrame.length; i++) {
                            if (i > 0) formattedHex.append(" ");
                            formattedHex.append(String.format("%02X", lastFrame[i]));
                        }
                        Files.write(cfgFile.toPath(),
                                formattedHex.toString().getBytes(StandardCharsets.UTF_8));
                    } else {
                        data.add(new ConfigParam("错误", "尚未读取到配置帧，请先点击【刷新】", ""));
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