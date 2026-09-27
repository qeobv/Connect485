package org.example;

/**
 * 启动器：绕过 JavaFX 的 classpath 限制
 * 所有 maven-jar-plugin / jpackage 的 main-class 都指向这个类
 */
public class Launcher {
    public static void main(String[] args) {
        Main.main(args);   // 调用你真正的 JavaFX Application 子类的 main
    }
}