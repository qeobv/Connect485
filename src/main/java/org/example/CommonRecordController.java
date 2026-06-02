package org.example;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

public class CommonRecordController<T> { // 使用泛型，适配不同类型的表格数据

    // 需要在子类的 FXML 中定义一个 fx:id="recordTable" 的 TableView
    @FXML
    protected TableView<T> recordTable;

    private SerialManager manager;

    /**
     * 注入 SerialManager 单例
     */
    public void setManager(SerialManager manager) {
        this.manager = manager;
    }

    @FXML
    public void initialize() {
        // 通用的初始化逻辑：设置表格无数据时的占位符
        if (recordTable != null) {
            recordTable.setPlaceholder(new javafx.scene.control.Label("暂无数据记录"));
        }
    }

    /**
     * 通用的关闭窗口逻辑
     */
    @FXML
    private void onCloseAction(ActionEvent event) {
        ((Stage) ((Node) event.getSource()).getScene().getWindow()).close();
    }

    /**
     * 通用的下载逻辑 -> 核心调用底层的 exportTextData
     */
    @FXML
    private void onDownloadAction(ActionEvent event) {
        if (manager == null) {
            manager = SerialManager.getInstance(); // 保底获取单例
        }

        if (recordTable == null || recordTable.getItems().isEmpty()) {
            System.out.println("表格为空，无需下载");
            return;
        }

        // 1. 将 TableView 的数据转换为纯文本 (CSV格式)
        String textContent = convertTableToText();

        // 2. 生成默认文件名 (例如：记录数据_20231024_153000.txt)
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String initialFileName = "记录数据_" + timestamp + ".csv";

        // 3. 获取当前窗口用于弹窗的 Owner
        Window ownerWindow = ((Node) event.getSource()).getScene().getWindow();

        // 4. 调用底层 SerialManager 的通用下载方法
        boolean success = manager.exportTextData(textContent, initialFileName, ownerWindow);

        if (success) {
            System.out.println("数据导出成功");
        }
    }

    /**
     * 通用的刷新逻辑
     */
    @FXML
    private void onRefreshAction(ActionEvent event) {
        // 1. 清空当前界面显示
        if (recordTable != null) {
            recordTable.getItems().clear();
        }
        // 2. 重新拉取数据 (交由具体的子类实现)
        refreshData();
    }

    /**
     * 将表格数据转为文本格式的方法 (CSV 样式，用 Tab 分隔更便于 txt 查看)
     */
    private String convertTableToText() {
        StringBuilder sb = new StringBuilder();

        // 拼接表头 【修改这里】改为逗号分隔
        String header = recordTable.getColumns().stream()
                .map(col -> col.getText())
                .collect(Collectors.joining(","));
        sb.append(header).append("\n");

        // 拼接数据行 【修改这里】改为逗号分隔
        for (T item : recordTable.getItems()) {
            String row = recordTable.getColumns().stream()
                    .map(col -> {
                        Object cellValue = col.getCellObservableValue(item).getValue();
                        // 【增强这里】如果数据本身包含逗号，需要用双引号包裹，否则会破坏CSV结构
                        String valueStr = (cellValue == null ? "" : cellValue.toString());
                        if (valueStr.contains(",")) {
                            valueStr = "\"" + valueStr + "\"";
                        }
                        return valueStr;
                    })
                    .collect(Collectors.joining(","));
            sb.append(row).append("\n");
        }

        return sb.toString();
    }


    /**
     * 刷新数据的钩子方法（由具体的子控制器覆写）
     * 因为不同界面的数据来源（发什么串口指令）不一样
     */
    protected void refreshData() {
        // 默认空实现
        // 例如：if (manager != null) { manager.sendData(...); }
    }
}
