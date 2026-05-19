package org.example;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) { e.printStackTrace(); }

        SwingUtilities.invokeLater(() -> {
            SerialAppGUI view = new SerialAppGUI();
            SerialService model = new SerialService();
            SerialController controller = new SerialController(view, model);

            view.setVisible(true);
        });
    }
}

