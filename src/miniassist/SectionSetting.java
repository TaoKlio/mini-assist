package miniassist;

import arc.Core;
import arc.scene.ui.layout.Scl;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * 设置页里的一行「分节标题 + 说明」。
 *
 * <p>一等分节（构造参数 {@code sub = false}）渲染成**强调色标题 + 横向分隔线**，与游戏自己的
 * {@code BaseDialog} 标题线同款画法（{@code image(Tex.whiteui, Pal.accent).height(3f)}），
 * 让不同功能在长列表里一眼可分；{@code sub = true} 用于 feature 内部的分组说明（如「净输出」口径），
 * 保持浅色小字、不画线，避免与一等分节抢视觉。
 *
 * <p>为什么不直接 {@code t.add(...)}：{@code SettingsTable} 每添加一个设置项都会
 * {@link SettingsTable#rebuild()} —— 它会 {@code clearChildren()} 后按内部 {@code list} 重建整张表，
 * 直接塞进去的控件会被下一次 rebuild 冲掉。因此必须实现为 {@link SettingsTable.Setting}
 * 并用 {@code pref()} 注册，才能稳定留在表里。
 *
 * <p>文案取自 bundle：{@code setting.<name>.name} 作标题、{@code setting.<name>.description} 作说明。
 * 构造后把 {@code name} 置空：本行不对应任何落盘设置项，这样「重置为默认值」按钮会跳过它
 * （该按钮对 {@code name == null} 的项直接 continue）。
 */
public class SectionSetting extends SettingsTable.Setting{
    /** true = 子标题（更小、更浅，不画分隔线），用于同一个 feature 内部的分组说明。 */
    private final boolean sub;

    /** 一等分节标题：用于 feature 之间的分隔（「钻机产出」「内存块读写」）。 */
    public SectionSetting(String name){
        this(name, false);
    }

    /**
     * @param sub true 时渲染为浅色子说明，用于 feature 内部的分组（如钻机产出的「净输出」口径说明）
     */
    public SectionSetting(String name, boolean sub){
        super(name);
        this.sub = sub;
        this.name = null;
    }

    @Override
    public void add(SettingsTable table){
        // 与设置项（CheckSetting 的 minWidth(500f)）对齐的宽度
        float width = Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));

        if(!sub){
            table.add(title, Styles.outlineLabel).color(Pal.accent).left().padTop(22f).get();
            table.row();
            table.image(Tex.whiteui, Pal.accent).width(width).height(3f).padTop(3f).padBottom(2f);
            table.row();
        }else{
            table.add(title, Styles.defaultLabel).left().padTop(14f).get();
            table.row();
        }

        if(description != null){
            table.add(description, Styles.defaultLabel).left().padTop(4f)
                .width(width).wrap().get();
            table.row();
        }
    }
}
