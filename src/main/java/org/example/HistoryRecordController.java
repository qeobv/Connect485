package org.example;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.scene.control.cell.PropertyValueFactory;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class HistoryRecordController implements Initializable {
    private static final String PAGE_TAG = "HistoryRecordView";
    private static final String INIT_COMMAND = "01 66 00 00 00 00";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @FXML private TableView<RecordItem> recordTable;
    @FXML private TableColumn<RecordItem, String> timeColumn;
    @FXML private TableColumn<RecordItem, String> contentColumn;

    private SerialManager manager;
    private SerialManager.SerialEventListener myListener;
    private final ProtocolTranslationManager translationManager =
            ProtocolTranslationManager.getInstance();

    private final ObservableList<RecordItem> records = FXCollections.observableArrayList();
    private final List<String> fullRecords = new ArrayList<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        timeColumn.setCellValueFactory(cd -> cd.getValue().timeProperty());
        contentColumn.setCellValueFactory(cd -> cd.getValue().contentProperty());
        recordTable.setItems(records);
        recordTable.setPlaceholder(new Label("暂无记录，点击【刷新】获取"));
    }

    public void setManager(SerialManager manager) {
        this.manager = manager;
        if (myListener != null) return;

        myListener = new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                if (rawHex == null) return;

                // ★ 只处理接收，不处理发送
                if (rawHex.contains("发送]:")) return;
                if (!rawHex.contains("接收]:")) return;

                String translated = translationManager.translate(rawHex);
                if (translated == null || translated.trim().isEmpty()) return;
                if (translated.startsWith("不支持")) return;
                if (translated.startsWith("数据格式错误")) return;
                if (translated.contains("无历史记录")) return;

                String timestamp = LocalDateTime.now().format(TIME_FMT);
                fullRecords.add(String.format("[%s] %s", timestamp, translated));

                Platform.runLater(() ->
                        records.add(new RecordItem(timestamp, translated)));
            }

            @Override
            public void onTranslatedData(String translatedText) {
                // SerialManager 不再主动触发
            }

            @Override
            public void onSystemLog(String log) {
                // 历史页不显示系统日志
            }

            @Override
            public void onError(String error) {
                // 历史页不显示错误
            }
        };

        manager.addListener(PAGE_TAG, myListener);
        Platform.runLater(this::sendInitCommand);

        recordTable.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) onDestroy();
        });
    }

    private void sendInitCommand() {
        if (manager == null || !manager.isPortOpen()) return;
        try {
            byte[] dataToSend = hexToBytes(INIT_COMMAND);
            manager.sendData(dataToSend, PAGE_TAG);
        } catch (Exception e) {
            System.err.println("发送初始指令失败: " + e.getMessage());
        }
    }

    @FXML
    private void onRefreshAction(ActionEvent event) {
        records.clear();
        fullRecords.clear();
        sendInitCommand();
    }

    @FXML
    private void onDownloadAction(ActionEvent event) {
        if (fullRecords.isEmpty()) {
            showInfo("没有数据可下载");
            return;
        }
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String content = String.join("\n", fullRecords);
        String fileName = "历史记录_" + timestamp + ".txt";

        boolean success = manager.exportTextData(
                content, fileName, recordTable.getScene().getWindow());
        if (success) showInfo("数据导出成功");
    }

    @FXML
    private void onCloseAction(ActionEvent event) {
        onDestroy();
        ((Stage) recordTable.getScene().getWindow()).close();
    }

    public void onDestroy() {
        if (myListener != null && manager != null) {
            manager.removeListener(myListener);
            myListener = null;
        }
    }

    private byte[] hexToBytes(String hexString) {
        hexString = hexString.replaceAll("\\s+", "");
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i+1), 16));
        }
        return data;
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }
}