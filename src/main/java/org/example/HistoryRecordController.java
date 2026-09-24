package org.example;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class HistoryRecordController {
    @FXML
    private TableView<String> recordTable;
    @FXML
    private TableColumn<String, String> timeColumn;
    @FXML
    private TableColumn<String, String> contentColumn;

    private SerialManager manager;
    private final ObservableList<String> records = FXCollections.observableArrayList();
    private final List<String> fullRecords = new ArrayList<>();

    private static final String INIT_COMMAND = "01 66 00 00 00 00";

    public void setManager(SerialManager manager) {
        this.manager = manager;
    }

    @FXML
    public void initialize() {
        // 设置表格列
        timeColumn.setCellValueFactory(new PropertyValueFactory<>("time"));
        contentColumn.setCellValueFactory(new PropertyValueFactory<>("content"));
        recordTable.setItems(records);

        // 添加数据接收监听
        if (manager != null) {
            manager.addListener(new SerialManager.SerialEventListener() {
                @Override
                public void onRawData(String rawHex) {
                    // 不需要处理原始数据
                }

                @Override
                public void onTranslatedData(String translatedText) {
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
                    String record = String.format("[%s] %s", timestamp, translatedText);
                    fullRecords.add(record);
                    Platform.runLater(() -> records.add(record));
                }

                @Override
                public void onSystemLog(String log) {
                    // 可以选择处理系统日志
                }

                @Override
                public void onError(String error) {
                    // 可以选择处理错误信息
                }
            });
        }

        // 自动发送初始指令
        Platform.runLater(() -> {
            if (manager != null && manager.isPortOpen()) {
                try {
                    byte[] dataToSend = hexToBytes(INIT_COMMAND);
                    manager.sendData(dataToSend);
                } catch (Exception e) {
                    System.err.println("发送初始指令失败: " + e.getMessage());
                }
            }
        });
    }

    private byte[] hexToBytes(String hexString) {
        hexString = hexString.replace(" ", "");
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("HEX字符串长度必须是偶数");
        }

        byte[] bytes = new byte[hexString.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hexString.charAt(i * 2), 16);
            int low = Character.digit(hexString.charAt(i * 2 + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("包含非HEX字符");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    @FXML
    private void onCloseAction(ActionEvent event) {
        ((Stage) recordTable.getScene().getWindow()).close();
    }

    @FXML
    private void onDownloadAction(ActionEvent event) {
        if (fullRecords.isEmpty()) {
            System.out.println("没有数据可下载");
            return;
        }

        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String content = String.join("\n", fullRecords);
        String fileName = "历史记录_" + timestamp + ".txt";

        // 调用导出功能
        boolean success = manager.exportTextData(content, fileName, recordTable.getScene().getWindow());
        if (success) {
            System.out.println("数据导出成功");
        }
    }

    @FXML
    private void onRefreshAction(ActionEvent event) {
        records.clear();
        fullRecords.clear();
        // 重新发送初始指令
        if (manager != null && manager.isPortOpen()) {
            try {
                byte[] dataToSend = hexToBytes(INIT_COMMAND);
                manager.sendData(dataToSend);
            } catch (Exception e) {
                System.err.println("发送初始指令失败: " + e.getMessage());
            }
        }
    }
}
