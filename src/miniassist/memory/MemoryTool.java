package miniassist.memory;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.geom.*;
import mindustry.game.EventType.*;
import mindustry.gen.Icon;
import mindustry.graphics.*;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.*;
import mindustry.world.blocks.logic.MemoryBlock;
import miniassist.SectionSetting;

import static mindustry.Vars.*;

/**
 * 内存块读写助手 —— mini辅助 的「内存块读写」feature。
 *
 * <p>悬停内存块时在方块上方画两个可点击图标：左为读（整块复制到剪贴板）、右为写（从剪贴板写回数字槽）。
 * 仅单机可用（{@code net.active()} 时整体停用）。
 *
 * <p>本类不再是 mod 入口：入口是 {@code miniassist.MiniAssistMod}，它注册设置分类并调用
 * {@link #init()} 与 {@link #addSettings(SettingsTable)}。
 */
public class MemoryTool{
    // 图标边长与两图标中心间距（世界单位，1 格 = 8）
    private static final float SIZE = 8f, GAP = 12f;

    // 设置项 key：允许复制的内存块最大长度（槽位数）
    private static final String SETTING_MAXLEN = "memacc-maxlength";
    private static final int DEFAULT_MAXLEN = 16384;

    // 虚拟选中的内存块：鼠标移向图标时保持选中，避免 UI 因离开块格子而消失
    private MemoryBlock.MemoryBuild selected;

    /** 把「内存块读写」这一节的设置项挂到 mini辅助 的设置分类里。 */
    public void addSettings(SettingsTable t){
        t.pref(new SectionSetting("miniassist-memory"));

        t.textPref(SETTING_MAXLEN, String.valueOf(DEFAULT_MAXLEN));
    }

    /** 注册事件监听。设置项由 {@link #addSettings(SettingsTable)} 挂到 mini辅助 的设置分类里。 */
    public void init(){
        // 每帧在选中的内存块上方画两个可点击图标
        Events.run(Trigger.draw, () -> {
            if(!state.isPlaying() || net.active()) return;
            MemoryBlock.MemoryBuild b = selected;
            if(b == null) return;
            Draw.z(Layer.overlayUI);
            Draw.color(Color.white);
            Draw.rect(Icon.copy.getRegion(),  b.x - GAP/2f, b.y + GAP, SIZE, SIZE); // 读
            Draw.rect(Icon.paste.getRegion(), b.x + GAP/2f, b.y + GAP, SIZE, SIZE); // 写
        });

        Events.run(Trigger.update, () -> {
            if(!state.isPlaying() || net.active()) return;
            if(Core.scene == null || Core.scene.hasField()) return;
            if(ui.logic != null && ui.logic.isShown()) return;

            // 选中的块若被拆除/切图，则清除
            if(selected != null && !selected.isValid()) selected = null;

            Vec2 m = Core.input.mouseWorld();
            MemoryBlock.MemoryBuild hover = hovered();
            if(hover != null){
                selected = hover;                            // 鼠标在内存块上 → 选中
            }else if(selected != null && !withinZone(m, selected)){
                selected = null;                             // 移出「块+图标」区域 → 取消
            }

            if(selected == null) return;
            MemoryBlock.MemoryBuild b = selected;

            // 左键点击图标
            if(Core.input.keyTap(KeyCode.mouseLeft)){
                if(within(m, b.x - GAP/2f, b.y + GAP)){       // 点读图标
                    doRead(b);
                }else if(within(m, b.x + GAP/2f, b.y + GAP)){ // 点写图标
                    doWrite(b);
                }
            }

            // 快捷键兜底（可删）
            if(!Core.input.ctrl()) return;
            if(Core.input.keyTap(KeyCode.c)) doRead(b);
            else if(Core.input.keyTap(KeyCode.v)) doWrite(b);
        });
    }

    // 读：整块编码复制到剪贴板；超过设置上限则复制失败
    static void doRead(MemoryBlock.MemoryBuild b){
        int cap = MemoryIO.capacity(b);
        int max = maxLength();
        if(cap > max){
            ui.showInfoToast(Core.bundle.format("memoryaccess.copy-fail", cap, max), 1.5f);
            return;
        }
        Core.app.setClipboardText(MemoryIO.encodeAll(b));
        ui.showInfoToast(Core.bundle.get("memoryaccess.copied"), 1.2f);
    }

    // 写：从剪贴板解析并写回数字槽
    static void doWrite(MemoryBlock.MemoryBuild b){
        MemoryIO.Result r = MemoryIO.decodeAndWrite(Core.app.getClipboardText(), b);
        ui.showInfoToast(r.ok() ? Core.bundle.format("memoryaccess.wrote", r.written()) : Core.bundle.get("memoryaccess.parse-fail"), 1.2f);
    }

    // 读取设置的最大复制长度；非法/非正数值回退到默认
    static int maxLength(){
        try{
            int v = Integer.parseInt(Core.settings.getString(SETTING_MAXLEN, String.valueOf(DEFAULT_MAXLEN)).trim());
            return v > 0 ? v : DEFAULT_MAXLEN;
        }catch(Exception e){
            return DEFAULT_MAXLEN;
        }
    }

    // 命中单个图标
    static boolean within(Vec2 m, float cx, float cy){
        return Math.abs(m.x - cx) <= SIZE/2f && Math.abs(m.y - cy) <= SIZE/2f;
    }

    // 虚拟选中保持区域：覆盖块本体 + 上方两个图标（含边距）。
    // 这样鼠标从块移到图标上时不会丢失选中。
    static boolean withinZone(Vec2 m, MemoryBlock.MemoryBuild b){
        float halfW = GAP;            // 左右覆盖到图标外沿
        float bottom = b.y - SIZE/2f; // 块中心往下
        float top = b.y + GAP + SIZE; // 图标顶部
        return m.x >= b.x - halfW && m.x <= b.x + halfW
            && m.y >= bottom && m.y <= top;
    }

    static MemoryBlock.MemoryBuild hovered(){
        if(world == null) return null;
        Vec2 w = Core.input.mouseWorld();
        Tile t = world.tileWorld(w.x, w.y);
        return t != null && t.build instanceof MemoryBlock.MemoryBuild b ? b : null;
    }
}
