package org.example;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.text.Text;
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
        // 内容为多行文本：用 Text 作单元格内容（按列宽自动换行），
        // Text 的 prefHeight 精确，TableView 行高随内容自动撑开。
        contentColumn.setCellFactory(col -> new TableCell<RecordItem, String>() {
            private final Text text = new Text();
            {
                text.wrappingWidthProperty().bind(
                        contentColumn.widthProperty().subtract(16));
                setGraphic(text);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    text.setText(null);
                    setGraphic(null);
                } else {
                    text.setText(item);
                    setGraphic(text);
                }
            }
        });
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
                if (rawHex.contains("发送]:")) return;
                if (!rawHex.contains("接收]:")) return;

                // ★ 过滤 0x03
                String pureHex = rawHex.substring(rawHex.indexOf("]:") + 2)
                        .replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
                if (pureHex.length() >= 4 && "03".equals(pureHex.substring(2, 4))) {
                    return;
                }
                System.out.println("历史页收到: " + rawHex);
                String profileTranslation = manager.getProfile().translate(rawHex);
                // 当前系列尚无该帧的翻译（如 MTA 的 0x66 历史记录）时，原样显示原始报文
                final String translated = (profileTranslation != null) ? profileTranslation : rawHex;
                if (translated.trim().isEmpty()) return;
                if (translated.startsWith("不支持")) return;
                if (translated.startsWith("数据格式错误")) return;
                if (translated.contains("无历史记录")) return;

                String timestamp = LocalDateTime.now().format(TIME_FMT);
                fullRecords.add(String.format("[%s] %s", timestamp, translated));
                Platform.runLater(() -> records.add(new RecordItem(timestamp, translated)));
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
            Integer addr = manager.getDeviceAddress();
            if (addr == null) addr = 1;
            byte[] dataToSend = manager.getProfile().buildHistoryInitCommand(addr);
            if (dataToSend == null) {
                System.err.println("当前板卡系列不支持历史记录");
                return;
            }
            manager.sendData(dataToSend, PAGE_TAG);
        } catch (Exception e) {
            System.err.println("发送初始指令失败: " + e.getMessage());
            Platform.runLater(() -> records.add(new RecordItem(
                    LocalDateTime.now().format(TIME_FMT),
                    "查询命令发送失败：设备可能仍在推送数据（485 总线忙），请稍候点击【刷新】重试")));
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

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }
}