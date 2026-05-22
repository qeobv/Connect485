package org.example;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.stage.Stage;

public class CommonRecordController {
    @FXML
    public void initialize() {
        // 可以根据界面上的特定组件或加载来源判断当前是哪个页面
        // 执行通用的初始化逻辑
    }

    @FXML
    private void onCloseAction(ActionEvent event) {
        // 通用的关闭窗口逻辑
        ((Stage) ((Node) event.getSource()).getScene().getWindow()).close();
    }
}
