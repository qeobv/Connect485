package org.example;

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
        // 设置 AtlantaFX 主题
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        // 加载 FXML
        FXMLLoader loader = new FXMLLoader(getClass().getResource("SerialUI.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 900, 720);
        scene.getStylesheets().add(
                getClass().getResource("/org/example/style.css").toExternalForm()
        );

        primaryStage.setScene(scene);
        primaryStage.setTitle("Java 串口调试助手 v2.2.0");

        // 窗口关闭事件
        primaryStage.setOnCloseRequest(event -> {
            SerialUIController controller = loader.getController();
            if (controller != null) {
                controller.closeSerialPort();
            }
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}