package org.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 1. 创建 FXMLLoader 并加载 FXML 文件
        FXMLLoader loader = new FXMLLoader(getClass().getResource("SerialUI.fxml"));

        // 2. 加载 FXML 文件得到根节点
        Parent root = loader.load();

        // 3. 创建场景，并将根节点放入场景中，设置窗口的初始宽高
        Scene scene = new Scene(root, 900, 720);

        // 4. 将场景设置到主舞台
        primaryStage.setScene(scene);

        // 5. 设置窗口标题
        primaryStage.setTitle("Java 串口调试助手 (支持CRC校验开关 - JavaFX版)");

        // 6. 显示窗口
        primaryStage.show();
    }

    public static void main(String[] args) {
        // 启动 JavaFX 应用
        launch(args);
    }
}
