package org.example;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史记录页面控制器
 *
 * 修复要点：
 *  1. TableView<RecordItem> 代替 TableView<String>，两列才能正常显示
 *  2. 监听器注册移到 setManager()（initialize 时 manager 还是 null）
 *  3. 监听器带 PAGE_TAG，隔离其他页面的回包
 *  4. 发送指令带 PAGE_TAG，回包只发给本页面
 *  5. 关闭窗口时 onDestroy() 移除监听器，防止累积
 *  6. 布局用 VBox + VBox.vgrow 撑满表格，按钮右对齐
 */
public class HistoryRecordController {

    // ★ 页面唯一标识：其他页面用别的名字
    private static final String PAGE_TAG = "HistoryRecordView";

    // ★ 本页面只关心 01 66 的回包（协议原样，不修改）
    private static final String INIT_COMMAND = "01 66 00 00 00 00";

    // ============ FXML 绑定的控件 ============
    @FXML private TableView<RecordItem> recordTable;
    @FXML private TableColumn<RecordItem, String> timeColumn;
    @FXML private TableColumn<RecordItem, String> contentColumn;

    // ============ 内部字段 ============
    private SerialManager manager;
    private SerialManager.SerialEventListener myListener;   // ★ 保存引用以便移除

    private final ObservableList<RecordItem> records = FXCollections.observableArrayList();
    private final List<String> fullRecords = new ArrayList<>();   // 纯文本，用于导出

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ============================================================
    //  由父控制器调用：注入 manager 并注册监听器
    //  （不能在 initialize() 里注册，那时 manager 还是 null）
    // ============================================================
    public void setManager(SerialManager manager) {
        this.manager = manager;

        if (myListener != null) return;   // 防止重复注册

        myListener = new SerialManager.SerialEventListener() {

            @Override
            public void onRawData(String rawHex) {
                // 本页面不需要处理原始 hex
            }

            @Override
            public void onTranslatedData(String translatedText) {
                // 过滤掉空内容
                if (translatedText == null || translatedText.trim().isEmpty()) return;

                String timestamp = LocalDateTime.now().format(TIME_FMT);

                // 完整纯文本（用于导出）
                fullRecords.add(String.format("[%s] %s", timestamp, translatedText));

                // 表格行数据
                Platform.runLater(() ->
                        records.add(new RecordItem(timestamp, translatedText)));
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

        // ★ 带 PAGE_TAG 注册，隔离其他页面的回包
        manager.addListener(PAGE_TAG, myListener);

        // ★ manager 就绪后再发初始指令
        Platform.runLater(this::sendInitCommand);

        // ★ 窗口/Scene 被移除时自动清理（防止监听器泄漏）
        recordTable.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                onDestroy();
            }
        });
    }

    // ============================================================
    //  FXML 初始化（此时 manager 尚未注入）
    // ============================================================
    @FXML
    public void initialize() {
        // ★ 列绑定到 RecordItem 的属性（不用 PropertyValueFactory 反射）
        timeColumn.setCellValueFactory(cd -> cd.getValue().timeProperty());
        contentColumn.setCellValueFactory(cd -> cd.getValue().contentProperty());

        recordTable.setItems(records);

        // ★ 空表格占位提示
        recordTable.setPlaceholder(new Label("暂无记录，点击【刷新】获取"));
    }

    // ============================================================
    //  发送初始指令（带 tag）
    // ============================================================
    private void sendInitCommand() {
        if (manager == null || !manager.isPortOpen()) return;
        try {
            byte[] dataToSend = hexToBytes(INIT_COMMAND);
            manager.sendData(dataToSend, PAGE_TAG);   // ★ 带 tag
        } catch (Exception e) {
            System.err.println("发送初始指令失败: " + e.getMessage());
        }
    }

    // ============================================================
    //  页面销毁：移除监听器（防止泄漏 & 重复刷新）
    // ============================================================
    public void onDestroy() {
        if (myListener != null && manager != null) {
            manager.removeListener(myListener);
            myListener = null;
        }
    }

    // ============================================================
    //  hex → byte[] 工具
    // ============================================================
    private byte[] hexToBytes(String hexString) {
        String clean = hexString.replace(" ", "");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("HEX字符串长度必须是偶数");
        }
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(clean.charAt(i * 2), 16);
            int low  = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("包含非HEX字符");
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    // ============================================================
    //  按钮事件
    // ============================================================

    @FXML
    private void onCloseAction(ActionEvent event) {
        onDestroy();   // ★ 关闭前移除监听器
        ((Stage) recordTable.getScene().getWindow()).close();
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
        String fileName = "历史记录_" + timestamp + ".csv";   // ★ 与 exportTextData 的 csv 过滤一致

        boolean success = manager.exportTextData(
                content, fileName, recordTable.getScene().getWindow());
        if (success) {
            showInfo("数据导出成功");
        }
    }

    @FXML
    private void onRefreshAction(ActionEvent event) {
        records.clear();
        fullRecords.clear();
        sendInitCommand();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }
}