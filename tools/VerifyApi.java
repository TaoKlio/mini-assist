import java.lang.reflect.*;
import java.util.*;

/**
 * mini辅助 的 API 契约校验器（覆盖钻机产出 + 内存块读写两个 feature）。
 *
 * <p>用反射逐一确认 mod 引用的每个游戏 / Arc 成员真实存在，且签名完全匹配。
 * 这与 JVM 链接期检查等价：任何缺失都会在首次执行时报
 * NoSuchFieldError / NoSuchMethodError，此处提前把它们找出来。
 * 内存块读写走的是 {@code getDeclaredField} + {@code setAccessible}，因此那部分用
 * {@link #declaredField} 单独校验（{@code getField} 看不到私有成员）。
 *
 * <p>用法（classpath 必须同时包含 core-v160.jar / arc-core-v160.jar 与 libs 目录）：
 * <pre>
 * javac -encoding UTF-8 -d out tools\VerifyApi.java
 * java -cp "out;libs;core-v160.jar;arc-core-v160.jar" VerifyApi
 * </pre>
 */
public class VerifyApi{

    static int checked = 0, failed = 0;

    public static void main(String[] args){
        Class<?> drillBuild = cls("mindustry.world.blocks.production.Drill$DrillBuild");
        Class<?> drill      = cls("mindustry.world.blocks.production.Drill");
        Class<?> item       = cls("mindustry.type.Item");
        Class<?> itemModule = cls("mindustry.world.modules.ItemModule");
        Class<?> tile       = cls("mindustry.world.Tile");
        Class<?> world      = cls("mindustry.core.World");
        Class<?> gameState  = cls("mindustry.core.GameState");

        // --- Drills.java 读取的字段 ---
        field(drillBuild, "dominantItem", item);
        field(drillBuild, "dominantItems", int.class);
        field(drillBuild, "lastDrillSpeed", float.class);
        // 实际产出改成数 progress 的增量（public 字段）
        field(drillBuild, "progress", float.class);
        field(drillBuild, "warmup", float.class);
        field(drillBuild, "optionalEfficiency", float.class);
        field(drillBuild, "efficiency", float.class);
        field(drillBuild, "enabled", boolean.class);
        field(drillBuild, "items", itemModule);
        field(drillBuild, "block", cls("mindustry.world.Block"));
        field(drillBuild, "id", int.class);
        field(drillBuild, "x", float.class);
        field(drillBuild, "y", float.class);

        field(drill, "liquidBoostIntensity", float.class);
        field(item, "localizedName", String.class);
        // 行序排序的兜底键：净输出相同时按矿物 id 排，保证顺序确定
        // （v160 里 Content.id 是 short，不是 int——断言按实际类型写）
        field(item, "id", short.class);
        // 「净输出按游戏面板」口径复现的是 Drill.setStats() 的 60/drillTime × size²
        field(drill, "drillTime", float.class);
        field(cls("mindustry.world.Block"), "size", int.class);
        field(cls("mindustry.world.Block"), "itemCapacity", int.class);
        field(tile, "build", cls("mindustry.gen.Building"));
        field(cls("mindustry.Vars"), "world", world);
        field(cls("mindustry.Vars"), "state", gameState);
        field(cls("mindustry.Vars"), "ui", cls("mindustry.core.UI"));

        // --- 调用的方法 ---
        method(drill, "getDrillTime", float.class, item);
        method(drillBuild, "timeScale", float.class);
        method(itemModule, "total", int.class);
        method(world, "width", int.class);
        method(world, "height", int.class);
        method(world, "tile", tile, int.class, int.class);
        method(world, "toTile", int.class, float.class);
        method(gameState, "isPlaying", boolean.class);
        method(gameState, "isMenu", boolean.class);
        method(cls("arc.Input"), "keyDown", boolean.class, cls("arc.input.KeyCode"));
        method(cls("arc.Input"), "mouseWorld", cls("arc.math.geom.Vec2"));

        // --- Arc 静态工具 ---
        method(cls("arc.math.Mathf"), "lerp", float.class, float.class, float.class, float.class);
        method(cls("arc.util.Strings"), "fixed", String.class, float.class, int.class);
        method(cls("arc.graphics.g2d.Lines"), "stroke", void.class, float.class, cls("arc.graphics.Color"));
        method(cls("arc.graphics.g2d.Lines"), "rect", void.class, float.class, float.class, float.class, float.class);
        method(cls("arc.graphics.g2d.Draw"), "draw", void.class, float.class, Runnable.class);
        method(cls("arc.graphics.g2d.Draw"), "reset", void.class);
        method(cls("mindustry.graphics.Drawf"), "text", void.class, String.class, float.class, float.class, cls("arc.graphics.Color"), float.class);

        // --- 编译期就已确认、此处仅做存在性复核的字段 ---
        field(cls("mindustry.graphics.Pal"), "gray", cls("arc.graphics.Color"));
        field(cls("arc.graphics.Color"), "white", cls("arc.graphics.Color"));
        field(cls("mindustry.game.EventType$Trigger"), "update", cls("mindustry.game.EventType$Trigger"));
        field(cls("mindustry.game.EventType$Trigger"), "draw", cls("mindustry.game.EventType$Trigger"));
        field(cls("arc.input.KeyCode"), "f", cls("arc.input.KeyCode"));
        // 实际产出的逐帧时间积分用 Time.delta（60fps 帧数，与帧率无关）
        field(cls("arc.util.Time"), "delta", float.class);

        // --- 设置分类与「净输出」说明行 ---
        // 说明行必须实现为 SettingsTable.Setting 并走 pref()：SettingsTable.rebuild() 会
        // clearChildren() 后按内部 list 重建整张表，直接 add 出去的控件会被冲掉。
        Class<?> settingsDialog = cls("mindustry.ui.dialogs.SettingsMenuDialog");
        Class<?> settingsTable  = cls("mindustry.ui.dialogs.SettingsMenuDialog$SettingsTable");
        Class<?> setting        = cls("mindustry.ui.dialogs.SettingsMenuDialog$SettingsTable$Setting");
        Class<?> labelStyle     = cls("arc.scene.ui.Label$LabelStyle");
        Class<?> tableCls       = cls("arc.scene.ui.layout.Table");
        Class<?> cellCls        = cls("arc.scene.ui.layout.Cell");
        Class<?> settingsCls    = cls("arc.Settings");

        field(cls("mindustry.core.UI"), "settings", settingsDialog);
        method(settingsDialog, "addCategory", void.class, String.class, cls("arc.scene.style.Drawable"), cls("arc.func.Cons"));
        method(settingsTable, "pref", void.class, setting);
        method(settingsTable, "sliderPref",
            cls("mindustry.ui.dialogs.SettingsMenuDialog$SettingsTable$SliderSetting"),
            String.class, int.class, int.class, int.class, int.class,
            cls("mindustry.ui.dialogs.SettingsMenuDialog$StringProcessor"));
        ctor(setting, String.class);
        method(setting, "add", void.class, settingsTable);
        field(setting, "title", String.class);
        field(setting, "description", String.class);
        method(tableCls, "add", cellCls, CharSequence.class, labelStyle);
        // 分节标题的分隔线：与游戏 BaseDialog 标题线同款画法
        method(tableCls, "image", cellCls, cls("arc.scene.style.Drawable"), cls("arc.graphics.Color"));
        field(cls("mindustry.gen.Tex"), "whiteui", cls("arc.scene.style.Drawable"));
        field(cls("mindustry.graphics.Pal"), "accent", cls("arc.graphics.Color"));
        method(cellCls, "color", cellCls, cls("arc.graphics.Color"));
        method(cellCls, "height", cellCls, float.class);
        // 无灰底开关行：复用 Elems.check 再清掉背景（Table.setBackground(null)）
        method(cls("mindustry.ui.Elems"), "check", cls("arc.scene.ui.Button"),
            String.class, cls("arc.func.Boolp"), cls("arc.func.Boolc"));
        method(tableCls, "setBackground", void.class, cls("arc.scene.style.Drawable"));
        // 换掉 Styles.grayt（其 up = grayPanel 就是那块灰底）：需要空样式 + setStyle
        ctor(cls("arc.scene.ui.Button$ButtonStyle"));
        method(cls("arc.scene.ui.Button"), "setStyle", void.class, cls("arc.scene.ui.Button$ButtonStyle"));
        method(settingsCls, "defaults", void.class, Object[].class);
        field(cls("mindustry.ui.Styles"), "outlineLabel", labelStyle);
        field(cls("mindustry.ui.Styles"), "defaultLabel", labelStyle);
        method(settingsCls, "getBool", boolean.class, String.class, boolean.class);
        method(settingsCls, "getInt", int.class, String.class, int.class);

        // --- 镜头缩放（面板字号的屏幕尺寸补偿）---
        // 面板画在世界坐标里，会被镜头缩放一起放大缩小；用 getDisplayScale()（当前实际缩放，
        // 非 getScale() 的目标值）做反向补偿，才能让屏幕上的字号恒定。
        field(cls("mindustry.Vars"), "renderer", cls("mindustry.core.Renderer"));
        method(cls("mindustry.core.Renderer"), "getDisplayScale", float.class);
        method(cls("arc.scene.ui.layout.Scl"), "scl", float.class, float.class);

        // --- 文字测量（把面板收进选区，保证不越出边框）---
        Class<?> glyphLayout = cls("arc.graphics.g2d.GlyphLayout");
        Class<?> fontCls     = cls("arc.graphics.g2d.Font");
        Class<?> fontData    = cls("arc.graphics.g2d.Font$FontData");

        ctor(glyphLayout);
        method(glyphLayout, "setText", void.class, fontCls, CharSequence.class);
        field(glyphLayout, "width", float.class);
        field(cls("mindustry.ui.Fonts"), "outline", fontCls);
        field(cls("mindustry.ui.Fonts"), "def", fontCls);
        field(fontData, "scaleX", float.class);
        method(fontData, "setScale", void.class, float.class);
        method(fontCls, "getData", fontData);
        method(fontCls, "usesIntegerPositions", boolean.class);
        method(fontCls, "setUseIntegerPositions", void.class, boolean.class);

        // --- 内存块读写 feature 引用的成员 ---
        Class<?> memBuild   = cls("mindustry.world.blocks.logic.MemoryBlock$MemoryBuild");
        Class<?> building   = cls("mindustry.gen.Building");
        Class<?> arcInput   = cls("arc.Input");
        Class<?> appCls     = cls("arc.Application");
        Class<?> uiCls      = cls("mindustry.core.UI");
        Class<?> logicDlg   = cls("mindustry.logic.LogicDialog");
        Class<?> netCls     = cls("mindustry.net.Net");
        Class<?> drawCls    = cls("arc.graphics.g2d.Draw");
        Class<?> region     = cls("arc.graphics.g2d.TextureRegion");
        Class<?> regionDraw = cls("arc.scene.style.TextureRegionDrawable");
        Class<?> keyCodeCls = cls("arc.input.KeyCode");
        Class<?> color      = cls("arc.graphics.Color");

        // MemoryIO 的全部反射依赖：两个私有数组字段 + 静态哨兵对象，都必须存在且可 setAccessible
        declaredField(memBuild, "objectMemory", Object[].class);
        declaredField(memBuild, "numberMemory", double[].class);
        declaredField(memBuild, "sentinel", Object.class, true);
        method(memBuild, "isValid", boolean.class);
        field(building, "x", float.class);
        field(building, "y", float.class);
        // 「接了水」的判定改用液罐存量（满仓时不吸水，optionalEfficiency 会掉 0）
        Class<?> liquidModule = cls("mindustry.world.modules.LiquidModule");
        field(building, "liquids", liquidModule);
        method(liquidModule, "currentAmount", float.class);

        // 跨帧状态表（停机 / 接水判定的滞回用）
        Class<?> intMap = cls("arc.struct.IntMap");
        Class<?> intSeq = cls("arc.struct.IntSeq");
        ctor(intMap);
        method(intMap, "get", Object.class, int.class);
        method(intMap, "put", Object.class, int.class, Object.class);
        method(intMap, "remove", Object.class, int.class);
        ctor(intSeq);
        method(intSeq, "add", void.class, int.class);
        method(intSeq, "get", int.class, int.class);
        method(intSeq, "clear", void.class);

        // 图标绘制与点击判定
        field(cls("mindustry.gen.Icon"), "copy", regionDraw);
        field(cls("mindustry.gen.Icon"), "paste", regionDraw);
        method(regionDraw, "getRegion", region);
        field(cls("arc.Core"), "scene", cls("arc.scene.Scene"));
        method(cls("arc.scene.Scene"), "hasField", boolean.class);
        field(cls("arc.Core"), "app", appCls);
        method(appCls, "getClipboardText", String.class);
        method(appCls, "setClipboardText", void.class, String.class);
        method(uiCls, "showInfoToast", void.class, String.class, float.class);
        field(uiCls, "logic", logicDlg);
        method(logicDlg, "isShown", boolean.class);
        method(arcInput, "keyTap", boolean.class, keyCodeCls);
        method(arcInput, "ctrl", boolean.class);
        field(keyCodeCls, "c", keyCodeCls);
        field(keyCodeCls, "v", keyCodeCls);
        field(keyCodeCls, "mouseLeft", keyCodeCls);
        method(world, "tileWorld", tile, float.class, float.class);
        field(cls("mindustry.Vars"), "net", netCls);
        method(netCls, "active", boolean.class);
        field(cls("mindustry.graphics.Layer"), "overlayUI", float.class);
        method(drawCls, "z", void.class, float.class);
        method(drawCls, "color", void.class, color);
        method(drawCls, "rect", void.class, region, float.class, float.class, float.class, float.class);

        System.out.println("========================================");
        System.out.println("检查成员数: " + checked + " | 失败: " + failed);
        if(failed == 0){
            System.out.println("结果: 全部通过，mod 与 v160 API 契约一致");
        }else{
            System.out.println("结果: 存在 " + failed + " 处不匹配，必须修正");
        }
        System.out.println("========================================");
        if(failed > 0) System.exit(1);
    }

    static Class<?> cls(String name){
        try{
            return Class.forName(name, false, VerifyApi.class.getClassLoader());
        }catch(Throwable t){
            checked++;
            failed++;
            System.out.println("[缺失类型] " + name + "  (" + t + ")");
            return Object.class;
        }
    }

    static void field(Class<?> owner, String name, Class<?> type){
        checked++;
        try{
            Field f = owner.getField(name);
            if(!f.getType().equals(type)){
                failed++;
                System.out.println("[类型不符] " + owner.getName() + "." + name
                    + "  期望 " + type.getName() + " 实际 " + f.getType().getName());
                return;
            }
            // 静态/实例皆可，但必须是 public（否则 getfield 会在运行时抛 IllegalAccessError）
            if(!Modifier.isPublic(f.getModifiers())){
                failed++;
                System.out.println("[修饰符不符] " + owner.getName() + "." + name + " 非 public");
                return;
            }
            System.out.println("[OK 字段] " + owner.getSimpleName() + "." + name
                + (Modifier.isStatic(f.getModifiers()) ? " (static)" : "") + " : " + type.getSimpleName());
        }catch(Throwable t){
            failed++;
            System.out.println("[缺失字段] " + owner.getName() + "." + name + "  (" + t + ")");
        }
    }

    static void method(Class<?> owner, String name, Class<?> ret, Class<?>... params){
        checked++;
        try{
            Method m = owner.getMethod(name, params);
            if(!m.getReturnType().equals(ret)){
                failed++;
                System.out.println("[返回类型不符] " + owner.getName() + "." + name
                    + "  期望 " + ret.getName() + " 实际 " + m.getReturnType().getName());
                return;
            }
            System.out.println("[OK 方法] " + owner.getSimpleName() + "." + name + " -> " + ret.getSimpleName());
        }catch(Throwable t){
            failed++;
            System.out.println("[缺失方法] " + owner.getName() + "." + name + "(" + Arrays.toString(params) + ")  (" + t + ")");
        }
    }

    /**
     * 校验 private / protected 字段存在、类型匹配，且能被 {@code setAccessible(true)} 打开。
     *
     * <p>内存块读写 feature 靠反射读 {@code MemoryBlock.MemoryBuild} 的私有字段，
     * 这类依赖 {@code getField()} 找不到，必须用 {@code getDeclaredField()} 单独校验——
     * 它等价于 JVM 里真正会执行的那次反射访问。
     */
    static void declaredField(Class<?> owner, String name, Class<?> type){
        declaredField(owner, name, type, false);
    }

    /**
     * @param requireStatic true 时额外要求字段是 static（如 {@code MemoryBuild.sentinel}，
     *                      调用方用 {@code get(null)} 读取它）
     */
    static void declaredField(Class<?> owner, String name, Class<?> type, boolean requireStatic){
        checked++;
        try{
            Field f = owner.getDeclaredField(name);
            if(!f.getType().equals(type)){
                failed++;
                System.out.println("[类型不符] " + owner.getName() + "." + name
                    + "  期望 " + type.getName() + " 实际 " + f.getType().getName());
                return;
            }
            if(requireStatic && !Modifier.isStatic(f.getModifiers())){
                failed++;
                System.out.println("[修饰符不符] " + owner.getName() + "." + name + " 不是 static");
                return;
            }
            try{
                f.setAccessible(true);
            }catch(Throwable t){
                failed++;
                System.out.println("[不可访问] " + owner.getName() + "." + name + " setAccessible 失败 (" + t + ")");
                return;
            }
            System.out.println("[OK 私有字段] " + owner.getSimpleName() + "." + name
                + (Modifier.isStatic(f.getModifiers()) ? " (static)" : "") + " : " + type.getSimpleName());
        }catch(Throwable t){
            failed++;
            System.out.println("[缺失私有字段] " + owner.getName() + "." + name + "  (" + t + ")");
        }
    }

    /** 校验 public 构造函数存在 —— 供 mod 继承的 settings 说明行使用。 */
    static void ctor(Class<?> owner, Class<?>... params){
        checked++;
        try{
            Constructor<?> c = owner.getConstructor(params);
            if(!Modifier.isPublic(c.getModifiers())){
                failed++;
                System.out.println("[修饰符不符] " + owner.getName() + " 构造函数非 public");
                return;
            }
            System.out.println("[OK 构造] " + owner.getSimpleName() + "(" + Arrays.toString(params) + ")");
        }catch(Throwable t){
            failed++;
            System.out.println("[缺失构造] " + owner.getName() + "(" + Arrays.toString(params) + ")  (" + t + ")");
        }
    }
}
