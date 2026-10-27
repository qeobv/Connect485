package org.example;

import org.example.core.BoardProfile;
import org.example.g3.G3BoardProfile;
import org.example.s1z.S1ZBoardProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 板卡系列注册表。
 *
 * 内置系列在此静态注册；后续可扩展为：
 *   - 从 classpath 的 profiles/<id>.json 加载数据差异
 *   - ServiceLoader 加载外部插件 jar
 */
public final class BoardProfiles {

    private static final Map<String, BoardProfile> REGISTRY = new LinkedHashMap<>();

    static {
        register(new G3BoardProfile());
        register(new S1ZBoardProfile());
    }

    private BoardProfiles() {
    }

    public static void register(BoardProfile profile) {
        if (profile == null) return;
        REGISTRY.put(profile.id(), profile);
    }

    public static BoardProfile get(String id) {
        return REGISTRY.get(id);
    }

    public static List<BoardProfile> all() {
        return List.copyOf(REGISTRY.values());
    }

    /** 默认系列（未选择时使用） */
    public static BoardProfile defaultProfile() {
        BoardProfile profile = REGISTRY.get("g3");
        if (profile == null) {
            throw new IllegalStateException("未注册任何板卡系列");
        }
        return profile;
    }
}
