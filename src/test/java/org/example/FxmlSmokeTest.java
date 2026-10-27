package org.example;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.stage.Window;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * FXML 冒烟测试：在 FX 线程加载全部界面并实例化控制器，
 * 验证 fx:id 绑定与 onAction 方法存在（FXML 语法/控制器错误会抛异常）。
 *
 * 测试环境没有真机串口时，SerialUIController.initialize 会弹"未发现串口设备"
 * 的提示框，由后台线程自动关闭，避免测试挂起。
 */
public class FxmlSmokeTest {

    @Test
    public void loadAllViews() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue("JavaFX 平台启动超时", started.await(10, TimeUnit.SECONDS));

        // 自动关闭测试期间弹出的提示框（真实运行时由用户点击关闭）
        Thread autoCloser = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
                Platform.runLater(() -> {
                    // 先拷贝再关闭：hide() 会把窗口从列表移除，直接迭代会越界
                    java.util.List<Window> windows = new java.util.ArrayList<>(Window.getWindows());
                    for (Window w : windows) {
                        if (w.isShowing()) w.hide();
                    }
                });
            }
        });
        autoCloser.setDaemon(true);
        autoCloser.start();

        String[] fxmls = {
                "SerialUI.fxml",
                "parameters_view.fxml",
                "history_view.fxml",
                "DebugView.fxml"
        };
        for (String fxml : fxmls) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<FXMLLoader> loaderRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/" + fxml));
                    assertNotNull(fxml + " 资源缺失", loader.getLocation());
                    loader.load();   // 语法/控制器/绑定错误会在此抛出
                    loaderRef.set(loader);
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    done.countDown();
                }
            });
            assertTrue(fxml + " 加载超时", done.await(15, TimeUnit.SECONDS));
            if (errorRef.get() != null) {
                throw new AssertionError(fxml + " 加载失败", errorRef.get());
            }
            assertNotNull(fxml + " 控制器未实例化", loaderRef.get().getController());
        }

        autoCloser.interrupt();
    }
}
