package org.example;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // ★ 设置 AtlantaFX 主题（必须在加载 FXML / 创建任何控件之前）
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        // 加载 FXML
        FXMLLoader loader = new FXMLLoader(getClass().getResource("SerialUI.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 900, 720);

        // 可选：加载你自己的微调 CSS（如果不需要可以删掉这两行）
        // scene.getStylesheets().add(
        //     getClass().getResource("/custom.css").toExternalForm()
        // );

        primaryStage.setScene(scene);
        primaryStage.setTitle("Java 串口调试助手 v2.1.0");

        // 添加窗口关闭事件处理
        primaryStage.setOnCloseRequest(event -> {
            // 获取控制器并关闭串口
            SerialUIController controller = loader.getController();
            if (controller != null) {
                controller.closeSerialPort();
            }
            // 确保 JavaFX 应用完全退出
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();
    }

    public static void main(String[] args) {
        // 启动 JavaFX 应用
        launch(args);
    }
}