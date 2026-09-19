package miniassist;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.Button;
import arc.scene.ui.layout.Scl;
import mindustry.ui.Elems;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * 与游戏勾选项同款的开关行，但**完全不画底色**。
 *
 * <p>灰底的来源有两层，都要去掉才算干净：
 * <ol>
 *   <li>{@link Elems#check(String, arc.func.Boolp, arc.func.Boolc)} 内部的
 *       {@code box.background(Styles.grayPanel)} —— 额外叠的一层 Table 背景；</li>
 *   <li>它构造按钮用的 {@code Styles.grayt} 样式本身就带 {@code up = grayPanel}
 *       （见 {@code Styles.java}）。{@code Button} 每帧都会按样式重设背景，
 *       所以只清 Table 背景没用——必须把**样式**换掉。</li>
 * </ol>
 *
 * <p>做法：复用游戏同一个 {@code Elems.check}（圆圈图标、悬停高亮、点击与联动行为全都保留），
 * 只是随后换上一个所有状态贴图都是 null 的 {@link Button.ButtonStyle}。
 * 注意不能去改 {@code Styles.grayt} 本身——那是全局共享对象，会把整个游戏的按钮一起改掉。
 *
 * <p>默认值登记与 {@code checkPref} 一致（{@link Core#settings} 的 {@code defaults}），
 * 这样设置页的「全部恢复默认」按钮仍然认得本项。与 {@link SectionSetting} 不同，
 * 这里的 {@code name} 是真实落盘设置键，不会被置空。
 */
public class PlainCheckSetting extends SettingsTable.Setting{
    /**
     * 全空按钮样式：up / over / down / checked / disabled 全是 null，因此不画任何底。
     * 新建一份实例而不是改 {@code Styles.grayt}，避免影响游戏其它界面。
     */
    private static final Button.ButtonStyle PLAIN_STYLE = new Button.ButtonStyle();

    private final boolean def;

    public PlainCheckSetting(String name, boolean def){
        super(name);
        this.def = def;
    }

    @Override
    public void add(SettingsTable table){
        // 与 SettingsTable.checkPref 相同的默认值登记，保证「全部恢复默认」生效
        Core.settings.defaults(name, def);

        Button box = Elems.check(title,
            () -> Core.settings.getBool(name, def),
            value -> Core.settings.put(name, value));
        // ← 关键：换掉带灰底贴图（Styles.grayt.up = grayPanel）的样式
        box.setStyle(PLAIN_STYLE);

        Element row = table.add(box).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f)))
            .fillX().height(45f).left().padTop(7f).get();
        addDesc(row);
        table.row();
    }
}
