package org.example;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 历史记录表格行数据模型
 *
 * TableView 的每一行是一个 RecordItem，
 * 通过 timeProperty() / contentProperty() 让 TableColumn 自动绑定刷新。
 */
public class RecordItem {

    private final StringProperty time;      // 时间列
    private final StringProperty content;   // 内容列

    public RecordItem(String time, String content) {
        this.time = new SimpleStringProperty(time);
        this.content = new SimpleStringProperty(content);
    }

    // ---- JavaFX 属性（TableColumn 绑定用）----
    public StringProperty timeProperty() { return time; }
    public StringProperty contentProperty() { return content; }

    // ---- 普通 getter/setter（导出、调试用）----
    public String getTime() { return time.get(); }
    public void setTime(String v) { time.set(v); }

    public String getContent() { return content.get(); }
    public void setContent(String v) { content.set(v); }
}