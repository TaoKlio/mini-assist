package miniassist;

import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import miniassist.drill.DrillOverlay;
import miniassist.memory.MemoryTool;

import static mindustry.Vars.ui;

/**
 * mini辅助 —— 把「钻机产出分析器」与「内存块读写助手」合二为一。
 *
 * <p>两个功能彼此独立、互不引用，各自注册自己的 {@code Trigger.update} / {@code Trigger.draw}：
 * <ul>
 *   <li>{@link DrillOverlay}：按住 F 框选，显示框内钻机的净输出与实际产出。
 *       纯客户端只读，联机可用，服务器无需安装；</li>
 *   <li>{@link MemoryTool}：悬停内存块时出现读/写图标，反射读写内存块内容。仅单机可用。</li>
 * </ul>
 *
 * <p>设置页只有「mini辅助」一个分类，内部按功能分节（分节标题由各 feature 用
 * {@link SectionSetting} 自己注册）。
 */
public class MiniAssistMod extends Mod{
    private final DrillOverlay drill = new DrillOverlay();
    private final MemoryTool memory = new MemoryTool();

    @Override
    public void init(){
        // 单一设置分类，按功能分节：钻机产出 → 内存块读写
        ui.settings.addCategory("@miniassist.settings", Icon.zoom, (SettingsTable t) -> {
            drill.addSettings(t);
            memory.addSettings(t);
        });

        // 事件监听：两个 feature 各注册各的，互不依赖
        drill.init();
        memory.init();
    }
}
