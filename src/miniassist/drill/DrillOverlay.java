package miniassist.drill;

import arc.Core;
import arc.Events;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.Fonts;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import miniassist.PlainCheckSetting;
import miniassist.SectionSetting;

/**
 * 钻机产出分析器 —— mini辅助 的「钻机产出」feature。
 *
 * <p>本类不再是 mod 入口：入口是 {@code miniassist.MiniAssistMod}，它负责注册设置分类并调用
 * {@link #init()} 与 {@link #addSettings(SettingsTable)}。
 *
 * <p>按住按键（默认 F）并移动鼠标张出选区框，实时统计框内所有钻机的理论产出与实际产出。
 *
 * <p><b>多人游戏</b>：本 mod 为纯客户端只读实现——只读取 {@code DrillBuild} 上由服务端同步的公开字段，
 * 不修改任何游戏状态、不发送任何数据包，因此：
 * <ul>
 *   <li>服务器无需安装本 mod，任意服务器均可使用；</li>
 *   <li>其他玩家看不到你的选区框；</li>
 *   <li>真·联机时读取到的是同步快照，数值可能比房主端略滞后。</li>
 * </ul>
 */
public class DrillOverlay{

    /** 选区键设置项 key（存的是 KeyCode 名称，如 "f"）。 */
    private static final String SETTING_KEY = "drilloutput-key";
    /** 是否启用。 */
    private static final String SETTING_ENABLED = "drilloutput-enabled";
    /** 是否给文字画深色描边（关闭可消除光晕/发糊）。 */
    private static final String SETTING_OUTLINE = "drilloutput-outline";
    /** 是否绘制选区矩形边框。默认关闭，界面更干净。 */
    private static final String SETTING_BOX = "drilloutput-box";
    /**
     * 是否显示选区范围文字（「选区 8x6 格」+ 截断提示）。默认开启。
     *
     * <p>与边框是**两个独立开关**；但文字不再跟鼠标飘——它画在选区内部当第一行
     * （见 {@link #drawSummary()}），随面板一起收进边框内。
     */
    private static final String SETTING_AREA_HINT = "drilloutput-area-hint";
    /** 选区边长上限（格）。 */
    private static final String SETTING_MAX_SIDE = "drilloutput-max-side";
    /** 是否对数值取平均后再显示（数据抖动大时开启）。 */
    private static final String SETTING_AVERAGE = "drilloutput-average";
    /** 取多少次采样求平均并固定。 */
    private static final String SETTING_AVERAGE_N = "drilloutput-average-n";

    /** 净输出是否计入「电力未连接」的钻机。 */
    private static final String SETTING_NET_NOPOWER = "drilloutput-net-nopower";
    /** 净输出是否计入「容量已满」的钻机。 */
    private static final String SETTING_NET_FULL = "drilloutput-net-full";
    /** 「净输出」说明行的本地化 key（仅用于取文案，不对应落盘设置项）。 */
    private static final String SETTING_NET_NOTE = "drilloutput-net-note";

    /**
     * 平均窗口默认值（次统计）。
     *
     * <p>取 30 ≈ 3 秒：判据是「下游消耗」——一个长周期量，窗口太短（如 0.7 秒）会与它对不上。
     */
    private static final int DEFAULT_AVERAGE_N = 30;
    /** 平均采样次数最小值：1 表示不平均，直接用瞬时值。 */
    private static final int MIN_AVERAGE_N = 1;
    /** 平均采样次数上限。 */
    private static final int MAX_AVERAGE_N = 1000;

    /**
     * 选区边长上限的可选范围与步长（格）。
     *
     * <p>遍历量按面积增长：上限 S 意味着每 6 帧最多检查 S² 格，200 是 4 万格、800 是 64 万格，
     * 后者在大地图上会有可感的掉帧，因此上限封在 800。
     */
    private static final int MIN_MAX_SIDE = 50;
    private static final int MAX_MAX_SIDE = 800;
    private static final int STEP_MAX_SIDE = 50;

    /** 默认按键。 */
    private static final KeyCode DEFAULT_KEY = KeyCode.f;

    /** 统计刷新间隔（帧）。显示滞后 ≤0.1 秒，肉眼不可辨，但大幅降低每帧开销。 */
    private static final int REFRESH_INTERVAL = 6;

    /**
     * 每边至少覆盖多少格才出数据。
     *
     * <p>取 **1**：只要框住一格就统计——单台钻机常常只占 1 格，「框一台机器看产出」是最常见的用法，
     * 旧值 2 会让这种小选区整块面板都不显示。只有选区**完全落在地图外**（{@code tilesX/tilesY == 0}）
     * 才视为「还没框出有效区域」，只留尺寸提示、不统计。
     */
    private static final int MIN_SIDE = 1;

    /** 行距（世界单位）。字体已按 0.25 缩放，10f 是紧凑但不重叠的间距。 */
    private static final float LINE_HEIGHT = 10f;

    /** 面板内容与选区边缘的间距（世界单位，随字号一起缩放）。 */
    private static final float PANEL_PAD = 5f;

    /**
     * 「收进选区」时允许缩小到的下限（相对镜头补偿后的屏幕字号）。
     *
     * <p>面板屏幕字号 = 镜头补偿系数 × 本比例。位图字体一旦被缩太多就会糊，
     * 所以这里把下限抬到 **0.7**：宁可让面板在极小选区里略微溢出，也不把字缩到看不清。
     * （镜头拉得很远 + 选区很小，正是两个机制相乘后最容易踩到下限的组合。）
     */
    private static final float MIN_FIT = 0.7f;

    /** 复用的文字测量器，避免每帧新建。 */
    private final GlyphLayout layout = new GlyphLayout();

    /**
     * 镜头缩放基准 = 游戏默认镜头。
     *
     * <p>取自 {@code Renderer.targetscale} 的初值 {@code Scl.scl(4f)}：在这个缩放下
     * 缩放系数恒为 1，外观与本 mod 早期版本一致。
     */
    private static final float DEFAULT_ZOOM = 4f;
    /** 缩放系数下限（镜头拉到最近时用得到）。 */
    private static final float MIN_TEXT_SCALE = 0.1f;
    /** 缩放系数上限（兜底，正常镜头范围内用不到：最远 0.5 倍时为 8）。 */
    private static final float MAX_TEXT_SCALE = 16f;

    /** 行内各段在矩形左边缘的固定偏移：图标、数值。 */
    private static final float ICON_OFFSET = 12f;
    private static final float VALUE_OFFSET = 24f;

    /** 图标绘制尺寸（世界单位）。 */
    private static final float ICON_SIZE = 7f;

    private static final Color BOX_COLOR = Color.valueOf("7ee7ff");
    private static final Color HINT_COLOR = Color.valueOf("ffd37f");
    private static final Color WAIT_COLOR = Color.valueOf("a0a0a0");

    /** 缓存的按键，避免每帧解析字符串。 */
    private KeyCode key = DEFAULT_KEY;
    private String cachedKeyName = "";

    /** 当前选区（世界坐标），null 表示没有选区。 */
    private boolean selecting;
    private Vec2 anchor = new Vec2();
    private float x1, y1, x2, y2;

    private final Drills result = new Drills();

    /** 距离上次统计刷新已过的帧数。 */
    private int frames;
    /** 最近一次有效框选的按键名，用于提示文案。 */
    private String activeKeyName = DEFAULT_KEY.name();

    /** 自增的刷新序号。 */
    private long refreshTick;

    /** 缓存的显示行（按矿物分组）。只在统计刷新时重建，绘制期零分配。 */
    private final Seq<Row> rows = new Seq<>();

    /** 连续多少次没被扫到就移除该行。 */
    private static final int MISS_LIMIT = 30;

    /**
     * 一行显示数据：矿物 + 数值。
     *
     * <p>两种显示模式：
     * <ul>
     *   <li><b>取平均（默认）</b>：累积 {@code window} 次采样求算术平均，取够后<b>把显示值固定住</b>；
     *       之后每凑满 window 次才更新一次，因此数字不会每帧跳动。</li>
     *   <li><b>不平滑</b>：直接用瞬时值。</li>
     * </ul>
     *
     * <p>两种模式通用的规则：<b>钻机台数一变就清空窗口并立即改用瞬时值</b>，这样增减矿机是即时生效的，
     * 不会出现指数拖尾那种古怪的渐变量。
     */
    private static class Row{
        final Item item;
        /** 当前块内已累积的采样次数。 */
        int size;
        /** 当前块内实际/净的累积和。 */
        float sumActual, sumNet;
        /** 显示值：取够 window 次平均后才更新，其余时间保持不变。 */
        float disActual, disNet;
        /** 刷新时格式化好的显示文本：绘制期不再做字符串格式化。 */
        String text = "";
        /** 该文本在 scale = 1 时的世界宽度，用于把面板收进选区。 */
        float textWidth;
        /** 最近一次原始钻机台数；变化说明矿机增减，需要立即按新值显示。 */
        int lastCount = -1;
        /** 最近一次被扫到的刷新序号（用于兜底移除）。 */
        long seenAt;
        /** 连续未出现的次数：一旦超过上限就删行，避免残留。 */
        int missed;

        Row(Item item){
            this.item = item;
        }

        /**
         * 推入一次原始采样。
         *
         * <p>块平均：累计 {@code windowSize} 次采样后取算术平均并更新显示值，
         * 随后累积和清零、开始下一个块。因此显示值在一个块内<b>完全不动</b>，
         * 只在块边界更新一次 —— 这正是"取多次平均后显示固定值"的效果。
         *
         * <p>钻机台数变化时立即重置并直接用当前瞬时值显示，保证增减矿机即时可见。
         */
        void push(float a, float n, int count, int windowSize){
            int cap = Math.max(1, windowSize);

            // 台数变化：丢弃半截的块，立刻显示新值
            if(count != lastCount){
                size = 0;
                sumActual = 0f;
                sumNet = 0f;
                lastCount = count;
                disActual = a;
                disNet = n;
                return;
            }

            sumActual += a;
            sumNet += n;
            size++;

            if(size >= cap){
                disActual = sumActual / size;
                disNet = sumNet / size;
                size = 0;
                sumActual = 0f;
                sumNet = 0f;
            }
        }
    }

    private Row rowFor(Item item){
        for(int i = 0; i < rows.size; i++){
            if(rows.get(i).item == item) return rows.get(i);
        }
        Row r = new Row(item);
        r.seenAt = refreshTick;
        rows.add(r);
        return r;
    }

    // ------------------------------------------------------------------ init

    /** 注册事件监听。设置项由 {@link #addSettings(SettingsTable)} 挂到 mini辅助 的设置分类里。 */
    public void init(){
        refreshKey();

        // 选区交互：只在客户端本地进行，不产生任何网络行为
        Events.run(Trigger.update, this::updateSelection);
        // 世界坐标绘制
        Events.run(Trigger.draw, this::drawOverlay);
    }

    /**
     * 把「钻机产出」这一节的设置项挂到 mini辅助 的设置分类里。
     *
     * <p>分类本身由 {@code MiniAssistMod} 创建，这里只负责分节标题与本 feature 的项。
     */
    public void addSettings(SettingsTable t){
        t.pref(new SectionSetting("miniassist-drill"));

        t.textPref(SETTING_KEY, DEFAULT_KEY.name(), v -> refreshKey());
        t.pref(new PlainCheckSetting(SETTING_ENABLED, true));

        // 净输出口径：先给一句定义，再给出两个「计入哪些停机钻机」的开关（默认都计入）
        t.pref(new SectionSetting(SETTING_NET_NOTE, true));
        t.pref(new PlainCheckSetting(SETTING_NET_NOPOWER, true));
        t.pref(new PlainCheckSetting(SETTING_NET_FULL, true));

        t.pref(new PlainCheckSetting(SETTING_OUTLINE, true));
        t.pref(new PlainCheckSetting(SETTING_BOX, false));
        t.pref(new PlainCheckSetting(SETTING_AREA_HINT, true));
        t.sliderPref(SETTING_MAX_SIDE, Drills.DEFAULT_MAX_SIDE, MIN_MAX_SIDE, MAX_MAX_SIDE, STEP_MAX_SIDE,
            v -> Core.bundle.format("drilloutput.side-value", v));
        t.pref(new PlainCheckSetting(SETTING_AVERAGE, true));
        t.sliderPref(SETTING_AVERAGE_N, DEFAULT_AVERAGE_N, MIN_AVERAGE_N, MAX_AVERAGE_N, 1, v -> v + "x");
    }

    private void refreshKey(){
        cachedKeyName = Core.settings.getString(SETTING_KEY, DEFAULT_KEY.name()).trim();
        KeyCode parsed = null;
        try{
            parsed = KeyCode.valueOf(cachedKeyName.toLowerCase());
        }catch(Exception ignored){
            // 名称非法则回退默认键
        }
        key = parsed == null ? DEFAULT_KEY : parsed;
        activeKeyName = key.name();
    }

    private boolean enabled(){
        return Core.settings.getBool(SETTING_ENABLED, true);
    }

    /** 是否给文字加深色描边。默认开启（与 v1.0 默认值一致）。 */
    private boolean outline(){
        return Core.settings.getBool(SETTING_OUTLINE, true);
    }

    /** 是否绘制选区边框。默认关闭，界面更干净。 */
    private boolean boxEnabled(){
        return Core.settings.getBool(SETTING_BOX, false);
    }

    /** 是否显示选区范围文字（框内第一行）。默认开启。 */
    private boolean areaHint(){
        return Core.settings.getBool(SETTING_AREA_HINT, true);
    }

    /**
     * 文字 / 图标缩放系数。
     *
     * <p>整个面板绘制在世界坐标（{@code Layer.overlayUI}），会跟着镜头缩放一起放大缩小：
     * 拉远看大矿区时字会缩成看不清的小点。开启本项后按镜头缩放做<b>反向补偿</b>——
     * {@code 基准镜头 / 当前镜头}，让文字与图标在<b>屏幕上的像素尺寸保持恒定</b>。
     *
     * <p>基准取游戏默认镜头 {@link #DEFAULT_ZOOM}（{@code Renderer.targetscale} 的初值），
     * 所以默认镜头下系数恒为 1，观感与本 mod 早期版本一致。
     *
     * <p>取 {@code getDisplayScale()}（当前实际缩放）而不是 {@code getScale()}（目标缩放）：
     * 镜头是 lerp 过渡的，用实际值才能在缩放动画的每一帧都保持屏幕字号不变、不抖。
     *
     * <p><b>这是「自动字号」规则的前一半</b>，不再单独提供开关：另一半是按选区收紧
     * {@link #fitScale}。两者相乘才得到最终字号——分开开关会让「屏幕字号恒定」与
     * 「不越出选区」互相打架（例如镜头拉很远时把字放大，再被选区缩小，屏幕字号被压到
     * 位图字体的可读线以下，就会糊）。
     */
    private float zoomScale(){
        // 除零兜底：极端情况下 getDisplayScale() 可能尚未初始化
        float zoom = Math.max(0.0001f, Vars.renderer.getDisplayScale());
        return Mathf.clamp(Scl.scl(DEFAULT_ZOOM) / zoom, MIN_TEXT_SCALE, MAX_TEXT_SCALE);
    }

    // ------------------------------------------------------------- selection

    private void updateSelection(){
        if(!enabled() || !Vars.state.isPlaying() || Vars.world == null){
            selecting = false;
            return;
        }

        if(!keyDown()){
            // 松手后保留最后一次的结果，方便查看
            selecting = false;
            return;
        }

        Vec2 mouse = Core.input.mouseWorld();

        if(!selecting){
            selecting = true;
            anchor.set(mouse);
            frames = REFRESH_INTERVAL;   // 首次按下立即统计一次
            result.resetAccumulators();  // 丢掉上一次框选残留的产出窗口
        }

        x1 = anchor.x;
        y1 = anchor.y;
        x2 = mouse.x;
        y2 = mouse.y;

        // 实际产出**逐帧**累加（时间积分），统计本身按帧节流——这样「实际」列是真正的
        // 时间平均，不会因为每 6 帧才抽样一次而对快速抖动产生混叠。
        result.accumulate();

        // 统计是遍历 + 字符串格式化，按帧节流；矩形本身每帧照常跟手
        if(++frames < REFRESH_INTERVAL) return;
        frames = 0;

        // 净输出口径：每次统计前读取开关，改设置后无需重开界面即时生效
        result.netIncludeNoPower = Core.settings.getBool(SETTING_NET_NOPOWER, true);
        result.netIncludeFull = Core.settings.getBool(SETTING_NET_FULL, true);
        // 「实际」口径：取平均值 = 时间平均（含停机时间）；关掉 = 瞬时速率（与游戏速度条同口径）
        result.averageActual = Core.settings.getBool(SETTING_AVERAGE, true);
        // 选区边长上限同理，并夹到合法区间（设置被手改成越界值也不怕）
        result.maxSide = Mathf.clamp(
            Core.settings.getInt(SETTING_MAX_SIDE, Drills.DEFAULT_MAX_SIDE), MIN_MAX_SIDE, MAX_MAX_SIDE);

        result.scan(x1, y1, x2, y2);

        // 选区完全无效（整块落在地图外）时不统计，只保留尺寸行
        if(result.tilesX < MIN_SIDE || result.tilesY < MIN_SIDE) return;

        computeSummary();
    }

    /**
     * 把统计结果按矿物分组并计算显示值；只在统计刷新时调用。
     *
     * <p>平均次数由设置项决定：开启"取平均值"时，累积 N 次采样求算术平均，
     * 取够后显示值保持稳定、每凑满一个窗口才更新一次；关闭时直接用瞬时值。
     * 两种模式下，钻机台数一变都会立即重置窗口并按新值显示。
     */
    private void computeSummary(){
        refreshTick++;

        boolean average = Core.settings.getBool(SETTING_AVERAGE, true);
        int windowSize = average
            ? Mathf.clamp(Core.settings.getInt(SETTING_AVERAGE_N, DEFAULT_AVERAGE_N), MIN_AVERAGE_N, MAX_AVERAGE_N)
            : 1;

        // 本轮出现的矿物：推入原始采样
        for(int i = 0; i < result.items.size; i++){
            Drills.ItemTotal t = result.items.get(i);
            Row r = rowFor(t.item);
            r.push(t.actual, t.net, t.count, windowSize);
            r.seenAt = refreshTick;
            r.missed = 0;
            // 文本与宽度在这里一次算好：绘制期零字符串格式化，同时供面板按选区收边
            r.text = Core.bundle.format("drilloutput.values", fixed(r.disNet), fixed(r.disActual));
            r.textWidth = measure(r.text);
        }

        // 本轮没出现的矿物：连续 MISS_LIMIT 次都没扫到再移除，避免瞬时漏扫导致行闪烁
        for(int i = rows.size - 1; i >= 0; i--){
            Row r = rows.get(i);
            if(r.seenAt == refreshTick) continue;
            if(++r.missed > MISS_LIMIT) rows.remove(i);
        }

        // 按**净输出**降序：净输出是稳定量，用它排序行序才不会随「实际输出」抖动来回跳
        // （时断时动的钻机会让实际输出上下穿行，两行就会反复交换位置）；
        // 净输出相同时用矿物 id 兜底，保证顺序确定
        rows.sort((a, b) -> {
            int cmp = Float.compare(b.disNet, a.disNet);
            return cmp != 0 ? cmp : Integer.compare(a.item.id, b.item.id);
        });
    }

    /** 按键轮询。key 在 {@link #refreshKey()} 中已保证非 null，无需异常兜底。 */
    private boolean keyDown(){
        return Core.input.keyDown(key);
    }

    // ---------------------------------------------------------------- render

    private void drawOverlay(){
        if(!enabled() || Vars.world == null || Vars.state.isMenu()) return;

        // 整块面板固定在 overlay 层绘制，避免文字/图标/边框落在不同层导致糊化与闪烁
        Draw.draw(Layer.overlayUI, () -> {
            if(selecting && boxEnabled()) drawBox();
            drawSummary();
        });
    }

    private void drawBox(){
        float minX = Math.min(x1, x2), minY = Math.min(y1, y2);
        float w = Math.abs(x2 - x1), h = Math.abs(y2 - y1);
        // 选区完全落在地图外（无有效格）时用灰色，表示"还不统计"
        Color color = (result.tilesX < MIN_SIDE || result.tilesY < MIN_SIDE) ? WAIT_COLOR : BOX_COLOR;

        Lines.stroke(3f, Pal.gray);
        Lines.rect(minX, minY, w, h);
        Lines.stroke(1f, color);
        Lines.rect(minX, minY, w, h);
        Draw.reset();
    }

    /** 选区尺寸文字（含截断提示）。原来跟在鼠标旁，现已并进选区内部当第一行。 */
    private String areaText(){
        return Core.bundle.format("drilloutput.area", result.tilesX, result.tilesY,
            result.clamped ? Core.bundle.format("drilloutput.area-clamped", result.rawTilesX, result.rawTilesY) : "");
    }

    /**
     * 面板：全部画在选区**内部**，从左上角往下排。
     *
     * <p>结构：框选过程中第一行是选区尺寸（就是原来跟鼠标的那行，现已并入），其后每行一种矿物，
     * 行结构为 {矿物图标}{净输出}{实际输出}；文字在刷新时格式化好，绘制期零字符串分配。
     *
     * <p>字号先按镜头补偿，再按选区尺寸收紧，保证文字**不越出边框**——这就是「文字超出方框」
     * 的修法：小选区会自动用小字号（下限 {@link #MIN_FIT}）。
     */
    private void drawSummary(){
        boolean live = selecting;
        boolean hasRows = !result.isEmpty() && !rows.isEmpty();
        if(!live && !hasRows) return;

        // 尺寸行只在框选过程中出现，且受「选区范围文字」开关控制（与边框无关）
        String area = (live && areaHint()) ? areaText() : null;
        int rowCount = hasRows ? rows.size : 0;

        // 面板自然尺寸（scale = 1，世界单位）
        float rowW = 0f;
        for(int i = 0; i < rowCount; i++) rowW = Math.max(rowW, rows.get(i).textWidth);
        float needW = Math.max(rowW, measure(area)) + VALUE_OFFSET;
        int lines = (area == null ? 0 : 1) + rowCount;
        float needH = lines * LINE_HEIGHT;

        float scale = fitScale(zoomScale(), needW, needH);
        float lineHeight = LINE_HEIGHT * scale;
        float pad = PANEL_PAD * scale;

        // 锚在选区左上角内侧；每帧按当前选区重算，拖动时不滞后
        float x = Math.min(x1, x2) + pad;
        float y = Math.max(y1, y2) - pad;

        if(area != null){
            drawLabel(area, x, y, BOX_COLOR, scale);
            y -= lineHeight;
        }

        for(int i = 0; i < rowCount; i++){
            Row r = rows.get(i);
            // 1) 矿物图标（标识是哪一种矿物）
            drawItemIcon(r.item, x + ICON_SIZE * scale * 0.5f, y, ICON_SIZE * scale);
            // 2) 数值：净输出在前、实际在后（净输出是主要参考量，放在最先看到的位置）
            drawLabel(r.text, x + VALUE_OFFSET * scale, y, Color.white, scale);
            y -= lineHeight;
        }

        // 本块绘制把混合模式改成正常了，收尾交还给游戏
        Draw.blend();
        Draw.reset();
    }

    /**
     * 把字号收进选区：面板在 scale = 1 时需要 {@code needW × needH}（世界单位），
     * 那么允许的最大字号就是 {@code min(可用宽/needW, 可用高/needH)}。
     *
     * <p>取它与镜头补偿字号的较小值，再兜一个 {@link #MIN_FIT} 下限——小选区宁可轻微溢出，
     * 也不把字缩到看不清。
     */
    private float fitScale(float zoomScale, float needW, float needH){
        float availW = Math.max(1f, Math.abs(x2 - x1) - 2f * PANEL_PAD);
        float availH = Math.max(1f, Math.abs(y2 - y1) - 2f * PANEL_PAD);

        float fit = Math.min(1f, Math.min(
            needW <= 0f ? 1f : availW / needW,
            needH <= 0f ? 1f : availH / needH));

        return zoomScale * Math.max(MIN_FIT, fit);
    }

    /** 量一段文字在 scale = 1 时的世界宽度（与 {@link #drawLabel} 同一字体与基准缩放）。 */
    private float measure(String text){
        if(text == null || text.isEmpty()) return 0f;

        Font font = outline() ? Fonts.outline : Fonts.def;
        boolean ints = font.usesIntegerPositions();
        float old = font.getData().scaleX;

        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f / Scl.scl(1f));
        layout.setText(font, text);
        float width = layout.width;

        font.getData().setScale(old);
        font.setUseIntegerPositions(ints);
        return width;
    }

    /**
     * 绘制一行文字。
     *
     * <p>关键点：<b>不让本行继承上一次绘制残留的层号</b>。Mindustry 按层做分块渲染，
     * 非 overlay 层会被绘制到低分辨率缓冲，文字就会发糊、并随层号漂移而"时隐时现"。
     * 这里强制 {@code Layer.overlayUI} 并显式关闭泛光混合，避免亮色文字被 bloom 括进去产生光晕。
     *
     * <p>字体默认用带描边的 {@code Fonts.outline}（与游戏原生世界文字一致）；若出现光晕可在设置里关闭。
     *
     * @param scale 额外缩放系数，1 为原始大小（用于"文字随选区缩放"）
     */
    private void drawLabel(String text, float x, float y, Color color, float scale){
        Font font = outline() ? Fonts.outline : Fonts.def;
        // 与 Drawf.text 保持一致的基准缩放，再乘上调用方给的系数
        float s = 0.25f / Scl.scl(1f) * scale;

        Draw.z(Layer.overlayUI);
        Draw.blend(Blending.normal);

        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(s);
        font.setColor(color);
        font.getCache().clear();
        font.getCache().addText(text, x, y, 0f, Align.left, false);
        font.getCache().draw();
        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }

    /** 绘制矿物图标。与文字同层，避免层号漂移导致闪烁。 */
    private void drawItemIcon(Item item, float x, float y, float size){
        if(item == null) return;
        Draw.z(Layer.overlayUI);
        Draw.blend(Blending.normal);
        Draw.color(Color.white);
        Draw.rect(item.fullIcon, x, y, size, size);
        Draw.color();
    }

    /** 统一保留 2 位小数。 */
    private static String fixed(float v){
        return arc.util.Strings.fixed(v, 2);
    }
}
