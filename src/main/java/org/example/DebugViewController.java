package org.example;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ResourceBundle;

public class DebugViewController implements Initializable {

    @FXML private TextArea txtDebugRecv;
    @FXML private TextArea txtSendData;
    @FXML private RadioButton rbSendHex;
    @FXML private CheckBox chkEnableCrc;
    @FXML private CheckBox chkTranslate;
    @FXML private Button btnImportData;
    @FXML private Button btnExportData;
    @FXML private Button btnSend;
    @FXML private Button btnClearDebugRecv;

    private SerialManager manager;
    private final ProtocolTranslationManager translationManager =
            ProtocolTranslationManager.getInstance();
    private SerialManager.SerialEventListener myListener;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        manager = SerialManager.getInstance();
        if (chkEnableCrc != null) chkEnableCrc.setSelected(true);
        if (chkTranslate != null) chkTranslate.setSelected(true);
        setupListeners();
    }

    private void setupListeners() {
        myListener = new SerialManager.SerialEventListener() {
            @Override
            public void onRawData(String rawHex) {
                if (!rawHex.startsWith("[")) return;
                Platform.runLater(() -> {
                    if (chkTranslate != null && chkTranslate.isSelected()) {
                        String translated = translationManager.translate(rawHex);
                        if (translated == null) {
                            // 翻译不了（碎片、请求帧等）→ 显示原始
                            txtDebugRecv.appendText(rawHex + "\n");
                        } else {
                            txtDebugRecv.appendText(translated + "\n");
                        }
                    } else {
                        txtDebugRecv.appendText(rawHex + "\n");
                    }
                });
            }

            @Override
            public void onTranslatedData(String translatedText) {
                // SerialManager 不再主动触发
            }

            @Override
            public void onSystemLog(String log) {
                Platform.runLater(() -> txtDebugRecv.appendText("[系统] " + log + "\n"));
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> txtDebugRecv.appendText("[错误] " + error + "\n"));
            }
        };
        manager.addListener("DebugView", myListener);
    }

    @FXML
    private void sendData() {
        if (!manager.isPortOpen()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先打开串口！");
            return;
        }

        String input = txtSendData.getText().trim();
        if (input.isEmpty()) return;

        try {
            String cleanHex = input.replace(" ", "").replace("\n", "").replace("\r", "");
            if (cleanHex.length() % 2 != 0) {
                showAlert(Alert.AlertType.ERROR, "格式错误", "HEX格式错误，长度必须为偶数！");
                return;
            }

            byte[] dataToSend = new byte[cleanHex.length() / 2];
            for (int i = 0; i < dataToSend.length; i++) {
                int high = Character.digit(cleanHex.charAt(i * 2), 16);
                int low = Character.digit(cleanHex.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) {
                    showAlert(Alert.AlertType.ERROR, "格式错误", "包含非HEX字符，请检查输入！");
                    return;
                }
                dataToSend[i] = (byte) ((high << 4) | low);
            }

            boolean crc = (chkEnableCrc == null) || chkEnableCrc.isSelected();
            manager.setCrcEnabled(crc);
            manager.sendData(dataToSend, "DebugView");

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "发送异常", e.getMessage());
        }
    }

    @FXML
    private void clearDebugRecv() {
        manager.clearTextAreas(txtDebugRecv);
    }

    @FXML
    private void importTxtData() {
        String content = manager.importTextData(btnImportData.getScene().getWindow());
        if (content != null) txtSendData.setText(content);
    }

    @FXML
    private void exportRecvData() {
        String content = txtDebugRecv.getText();
        if (content == null || content.trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "接收区为空，无数据可导出！");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出接收数据");
        chooser.setInitialFileName("debug_recv_" + timestamp + ".txt");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("CSV 文件 (*.csv)", "*.csv"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));

        File file = chooser.showSaveDialog(btnExportData.getScene().getWindow());
        if (file == null) return;

        try {
            byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, out, 0, bom.length);
            System.arraycopy(body, 0, out, bom.length, body.length);
            Files.write(file.toPath(), out);
            showAlert(Alert.AlertType.INFORMATION, "成功",
                    "接收数据已导出到:\n" + file.getAbsolutePath());
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "导出失败", e.getMessage());
        }
    }

    @FXML
    private void toggleTranslation() {
        // 只控制本页显示方式
    }

    public void onDestroy() {
        if (myListener != null && manager != null) {
            manager.removeListener(myListener);
            myListener = null;
        }
        if (txtDebugRecv != null) txtDebugRecv.clear();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}