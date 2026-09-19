package miniassist.drill;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;

/**
 * 钻机产出统计。
 *
 * <p>计算口径完全照搬 v160 {@code Drill.DrillBuild.updateTile()}，不引入近似：
 *
 * <pre>
 * delay       = drill.getDrillTime(dominantItem) = (drillTime + hardnessDrillMultiplier * hardness) / multiplier
 * items       = dominantItems                        // 钻机覆盖范围内可采矿石的格数
 * r           = lerp(0, 1, optionalEfficiency)        // 供水比例
 * liqBase     = 1 + (liquidBoostIntensity - 1) * r
 * liquidMul   = liqBase * liqBase                     // 加水加成是平方关系
 * speed       = liquidMul * efficiency
 * timeScale   = build.timeScale()                     // 超速倍率，delta() = Time.delta * timeScale
 * 净产出/秒    = 60 * speed * items / delay            // 只含加水，不含超速
 * 实际产出/秒  = 60 * lastDrillSpeed * timeScale       // 与游戏速度条口径一致
 * </pre>
 *
 * <p><b>加水加成要平方</b>：源码 {@code Drill.setStats()} 写的是
 * {@code liquidBoostIntensity * liquidBoostIntensity}，即满水时倍率为 {@code 1.8² = 3.24}（爆破钻），
 * 而不是 1.8。按一次方算会系统性低估 1.8 倍。
 *
 * <p><b>timeScale 不可省略</b>：{@code DrillBuild.updateTile()} 用 {@code delta()}（已含 timeScale）
 * 累加进度，而 {@code lastDrillSpeed} 只按帧计算、不含该倍率。游戏自身的速度条也是这么补的——
 * 见 {@code Drill.setBars()}：{@code Strings.fixed(e.lastDrillSpeed * 60 * e.timeScale(), 2)}。
 * 漏掉它会让实际产出低估超速倍率（例如超速 2.5 倍时读数只有真实的 40%）。
 *
 * <p>注意：{@code Building.applyBoost()} 只写 {@code timeScale}（超速是时间倍率），<b>不</b>写
 * {@code efficiency}，因此超速与效率不存在重复计数。
 *
 * <p><b>两个净输出开关</b>（{@link #netIncludeNoPower} / {@link #netIncludeFull}）只作用于聚合口径：
 * 关闭后，「电力未连接」或「容量已满」的钻机不再把各自的理论最大产出计入净输出合计，
 * 但 {@link Stat#maxNet} 本身仍保留该台钻机的真实理论值。停机原因按
 * 「被禁用 → 无矿 → 满仓 → 无电」取<b>首个命中</b>，因此同时满仓又缺电的钻机归类为满仓，
 * 只受 {@link #netIncludeFull} 影响。
 */
public class Drills{

    /** 按矿物种类聚合的一行数据。 */
    public static class ItemTotal{
        public Item item;
        /** 该矿物的实际产出合计（个/秒）。 */
        public float actual;
        /** 该矿物的净输出合计（个/秒，只含加水）。 */
        public float net;
        /** 参与净输出合计的钻机台数（被下面的开关排除的台数不计入）。 */
        public int count;

        public String fixedActual(){
            return Strings.fixed(actual, 2);
        }

        public String fixedNet(){
            return Strings.fixed(net, 2);
        }
    }

    /** 单台钻机的统计结果。 */
    public static class Stat{
        public DrillBuild build;
        public Drill drill;
        public Item item;
        public int oreCount;
        public float liquidMul;
        /**
         * 净产出（个/秒）= **额定理论产出**（满水、满效、满预热、不计超速），是一组定值。
         *
         * <p>只由「钻机配置 + 覆盖矿格数 + 接水标志」决定，见 {@link #theoreticalNet}。
         */
        public float maxNet;
        /** 实际产出（个/秒）：游戏 lastDrillSpeed × 60 × timeScale，停机（含被禁用 / 无矿）时归零。 */
        public float actual;
        /** 停机原因，null 表示未停机。 */
        public String stall;

        /** 实际 / 理论最大。有超速时可能大于 1（超速是时间倍率）。 */
        public float ratio(){
            return maxNet <= 0f ? 0f : actual / maxNet;
        }

        /** 格式化实际产出，保留 2 位小数。 */
        public String fixedActual(){
            return Strings.fixed(actual, 2);
        }

        /** 格式化理论最大产出，保留 2 位小数。 */
        public String fixedMaxNet(){
            return Strings.fixed(maxNet, 2);
        }
    }

    /** 选区边长上限的默认值（格）。 */
    public static final int DEFAULT_MAX_SIDE = 200;

    /**
     * 选区边长上限（格）。任一边超过它就截断，避免大范围遍历造成卡顿。
     *
     * <p>由设置项 {@code drilloutput-max-side} 决定，见 {@code DrillOutputMod}；
     * 这里仍做一次 `&lt; 1` 兜底，防止设置被手改成非法值。
     */
    public int maxSide = DEFAULT_MAX_SIDE;

    /** 净输出是否计入「电力未连接」的钻机（默认计入）。 */
    public boolean netIncludeNoPower = true;
    /** 净输出是否计入「容量已满」的钻机（默认计入）。 */
    public boolean netIncludeFull = true;

    /**
     * 「实际」列的两种口径（由设置「取平均值」决定）：
     *
     * <ul>
     *   <li><b>true（默认，时间平均）</b>：逐帧累加真实产出 ÷ 累计时间。
     *       这是「这台钻机平均每秒真的出了多少」——停机帧计 0，所以包含停机时间；</li>
     *   <li><b>false（瞬时速率）</b>：{@code 60 × lastDrillSpeed × timeScale}，
     *       与游戏自己的「钻取速度」条同口径。它回答的是「它现在跑多快」，
     *       停机时为 0，但**不把停机时间摊进平均**，所以读数会明显高于时间平均。</li>
     * </ul>
     *
     * <p>两种都是「真值」，只是问题不同。旧版本（每 6 帧抽样一次）之所以偏高，
     * 是因为抽样会**漏掉停机帧**——那是真错，不是口径。
     */
    public boolean averageActual = true;

    /**
     * 「判为停机」需要连续这么多次统计才采信（**悲观方向延迟**）。
     *
     * <p>面板每 6 帧统计一次，10 次约 1 秒。它只决定「这台钻机算不算停机」，
     * 而那只影响两个「计入 / 不计入」开关，**不影响净输出的数值**。
     */
    public static final int STALL_LATCH_SCANS = 10;

    /**
     * 判为「没接水」需要连续这么多次统计（约 5 秒）。
     *
     * <p>净输出是**额定值**，不该随水压抖动上下跳：所以「接了水」一旦观测到就置真，
     * 只有连续断水 5 秒才降为无加成。这样临时断供、水压不足都不会改变读数，
     * 而真正把水撤掉也会在几秒内反映出来。
     */
    public static final int DRY_LATCH_SCANS = 50;

    /** 多久没被扫到就丢弃该钻机的状态；避免拆建频繁的地图上状态表无限增长。 */
    private static final int PRUNE_SCANS = 300;

    /**
     * 「快钻」阈值（个/秒）：到这个量级一帧就可能产出 ≥2 件，
     * {@code progress} 的回绕无法精确还原，于是改用 {@code lastDrillSpeed} 积分口径。
     *
     * <p>一帧一件就是 60 件/秒，所以取略小一点的 55 作为切换点。
     */
    public static final float FAST_DRILL_RATE = 55f;

    /**
     * 「实际」的观测窗口与切分：**0.3 秒窗口，每 0.05 秒断一小片**。
     *
     * <p>0.05 秒 = 3 帧（60fps 单位，见 {@code Time.delta}），0.3 秒 = {@link #WINDOW_SLICES} 片。
     * 每片结束时记下该片的产出量，滑动窗口内各片相加后**除以窗口总时长**——
     * 即「0.3 秒内的速度和 ÷ 0.3」。
     *
     * <p>分母用**实际**累计时长而不是写死的 0.3：帧率波动 / 卡顿时仍然准确，
     * 而在 60fps 稳定时它就等于 0.3。
     */
    public static final float SLICE_FRAMES = 3f;
    /** 窗口内的片数：6 × 0.05 秒 = 0.3 秒。 */
    public static final int WINDOW_SLICES = 6;

    /** 跨帧保留的每台钻机状态，key = 建筑 id。 */
    private final IntMap<DrillState> states = new IntMap<>();
    /** 自增的统计序号，用于过期清理。 */
    private long scanTick;
    /** 复用的过期 key 缓冲。 */
    private final IntSeq stale = new IntSeq();

    /**
     * 单台钻机跨帧保留的状态 —— 都是**慢变量**，因为净输出要的是定值。
     *
     * <p>{@link #stall} 只服务两个「计入 / 不计入」开关；{@link #watered} 决定加成倍率，
     * 一旦接水就按满水额定，连续断水 {@link #DRY_LATCH_SCANS} 次（约 5 秒）才降。
     * 除此之外净输出完全由「钻机配置 + 覆盖矿格数」决定，不读任何瞬时状态。
     */
    private static class DrillState{
        /** 已采信的停机原因（null = 未停机）。 */
        String stall;
        /** 与 {@code stall} 不同的连续次数。 */
        int stallStreak;
        /** 是否按「接了水」计（一旦为真，连续断水 5 秒才转假）。 */
        boolean watered;
        /** 连续断水的次数。 */
        int dryStreak;
        /** 上一帧的 progress；用于算增量。 */
        float prevProgress;
        /** prevProgress 是否有效（刚进入选区 / 隔了几次统计没看到它时作废）。 */
        boolean hasProgress;

        // ---- 「实际」的 0.3 秒滑窗（每 0.05 秒一片）----
        /** 各小片的产出量（个）。 */
        final float[] sliceAmounts = new float[WINDOW_SLICES];
        /** 各小片的实际时长（帧），用于精确除以窗口总时长。 */
        final float[] sliceDurations = new float[WINDOW_SLICES];
        /** 正在累积的小片：产出量与时长。 */
        float sliceAmount, sliceTime;
        /** 环形写入位置。 */
        int sliceIndex;

        /** 最近一次被扫到的统计序号。 */
        long seenAt;
    }

    public final Seq<Stat> stats = new Seq<>();

    /**
     * 净输出（个/秒）= <b>额定理论产出</b>，逐台累加；**是一组定值**。
     *
     * <p>口径：**满水、满效、满预热、不计超速**，即
     * {@code 60 × liquidBoostIntensity² × dominantItems / getDrillTime(item)}。
     * 它只由「钻机配置 + 覆盖矿格数」决定，不读任何瞬时运行状态，因此只要钻机与地形不变，
     * 读数就是定值——这正是「理论」应有的性质。
     *
     * <p>唯一带入跨帧信息的只有「接没接水」的**滞回标志**（{@link #DRY_LATCH_SCANS}）：
     * 观测到接水就按满水额定，连续断水约 5 秒才降为无加成。水压抖动、临时断供都改不了读数。
     *
     * <p>停机（满仓 / 缺电 / 被禁用）只通过两个设置开关影响「算不算这台」，
     * **不影响数值本身**；真实产出（会抖、会归零）看「实际」那一列。
     */
    public float totalNet;
    /**
     * 实际输出（个/秒）：游戏真实产出（含超速、含启动爬升、含停机归零）。
     * 公式：60 * lastDrillSpeed * timeScale（与游戏"钻取速度"条同口径）
     */
    public float totalActual;

    public int activeCount;
    private float sumLiquidMul, sumEfficiency;

    /** 按矿物聚合的结果，用于面板逐行显示。 */
    public final Seq<ItemTotal> items = new Seq<>();
    private final arc.struct.ObjectMap<Item, ItemTotal> itemTotals = new arc.struct.ObjectMap<>();

    public int drillCount;
    /** 硬性停机台数（满仓 / 无矿 / 无电 / 被禁用）。 */
    public int stallCount;
    public int stallFull, stallNoOre, stallNoPower, stallDisabled;
    /** 未停机但仍处于启动爬升的台数（warmup &lt; 1）。 */
    public int warmupCount;

    public int tilesX, tilesY, rawTilesX, rawTilesY;
    public boolean clamped;

    private final IntSet settled = new IntSet();

    /**
     * 扫描世界坐标矩形内的全部钻机，并重置上一次的统计结果。
     *
     * <p>矩形会先对齐到格、夹取到地图范围；任一边超过 {@link #maxSide} 时截断并置 {@link #clamped}。
     */
    public Drills scan(float ax, float ay, float bx, float by){
        stats.clear();
        settled.clear();
        items.clear();
        itemTotals.clear();
        totalNet = totalActual = 0f;
        sumLiquidMul = sumEfficiency = 0f;
        activeCount = 0;
        drillCount = stallCount = warmupCount = 0;
        stallFull = stallNoOre = stallNoPower = stallDisabled = 0;
        clamped = false;
        tilesX = tilesY = rawTilesX = rawTilesY = 0;

        scanTick++;
        pruneStates();

        World world = Vars.world;
        if(world == null) return this;

        int w = world.width(), h = world.height();

        int minX = World.toTile(Math.min(ax, bx));
        int maxX = World.toTile(Math.max(ax, bx));
        int minY = World.toTile(Math.min(ay, by));
        int maxY = World.toTile(Math.max(ay, by));

        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(w - 1, maxX);
        maxY = Math.min(h - 1, maxY);

        rawTilesX = Math.max(0, maxX - minX + 1);
        rawTilesY = Math.max(0, maxY - minY + 1);

        // 上限兜底：设置项虽然限了范围，仍防止被手改成 0 / 负数
        int side = Math.max(1, maxSide);

        if(maxX - minX + 1 > side){
            maxX = minX + side - 1;
            clamped = true;
        }
        if(maxY - minY + 1 > side){
            maxY = minY + side - 1;
            clamped = true;
        }

        tilesX = Math.max(0, maxX - minX + 1);
        tilesY = Math.max(0, maxY - minY + 1);

        for(int y = minY; y <= maxY; y++){
            for(int x = minX; x <= maxX; x++){
                Tile tile = world.tile(x, y);
                if(tile == null || !(tile.build instanceof DrillBuild b)) continue;
                // 多格钻机只统计一次
                if(!settled.add(b.id)) continue;
                stats.add(compute(b, stabilize(b)));
            }
        }

        for(Stat s : stats){
            drillCount++;

            // 实际：停机时 lastDrillSpeed 已被游戏置 0，直接累加即可
            totalActual += s.actual;

            // 净输出 = 理论最大：满水、满效、不计超速；是否计入缺电 / 满仓的钻机由开关决定
            boolean inNet = countsTowardNet(s);
            if(inNet) totalNet += s.maxNet;

            if(s.stall != null){
                stallCount++;
                switch(s.stall){
                    case "full" -> stallFull++;
                    case "noore" -> stallNoOre++;
                    case "nopower" -> stallNoPower++;
                    case "disabled" -> stallDisabled++;
                    default -> {}
                }
            }else{
                activeCount++;
                sumLiquidMul += s.liquidMul;
                sumEfficiency += s.build.efficiency;
                if(s.build.warmup < 0.999f) warmupCount++;
            }

            // 按矿物聚合：每台矿机都建行（行集合保持稳定、不闪烁），
            // 但只有计入净输出的台数才累加 net —— 被排除时该行显示 0.00/秒
            if(s.item != null){
                ItemTotal t = itemTotals.get(s.item);
                if(t == null){
                    t = new ItemTotal();
                    t.item = s.item;
                    itemTotals.put(s.item, t);
                    items.add(t);
                }
                t.actual += s.actual;
                if(inNet){
                    t.net += s.maxNet;
                    t.count++;
                }
            }
        }

        // 按净输出降序（主要参考量），保证行序稳定
        items.sort((a, b) -> Float.compare(b.net, a.net));

        return this;
    }

    /**
     * 取（并更新）这台钻机的跨帧状态。
     *
     * <p>停机判定用**非对称滞回**（变好立即、变差连续 {@link #STALL_LATCH_SCANS} 次才采信），
     * 只服务两个「计入 / 不计入」开关，不参与净输出的数值。
     *
     * <p>接水标志同理但更宽：一旦接水就置真，连续断水 {@link #DRY_LATCH_SCANS} 次（约 5 秒）才降。
     * 首次见到某台钻机时直接采信当前状态，所以刚框选时不会有延迟。
     */
    private DrillState stabilize(DrillBuild b){
        DrillState st = states.get(b.id);
        if(st == null){
            st = new DrillState();
            st.stall = stallReason(b);
            st.watered = watered(b);
            states.put(b.id, st);
        }
        // 中间有统计没看到它（移出选区 / 刚进来）→ progress 基准作废，
        // 否则会拿陈旧基准算出一个假增量。
        if(scanTick - st.seenAt > 1) st.hasProgress = false;
        st.seenAt = scanTick;

        // 停机：恢复立即生效，判为停机要连续 STALL_LATCH_SCANS 次
        String nowStall = stallReason(b);
        boolean sameStall = nowStall == null ? st.stall == null : nowStall.equals(st.stall);
        if(sameStall){
            st.stallStreak = 0;
        }else if(nowStall == null){
            st.stall = null;
            st.stallStreak = 0;
        }else if(++st.stallStreak >= STALL_LATCH_SCANS){
            st.stall = nowStall;
            st.stallStreak = 0;
        }

        // 接水：接通立即采信；断水只在**确实在运转**时才算数
        if(watered(b)){
            st.watered = true;
            st.dryStreak = 0;
        }else if(st.stall != null){
            // 停机中（满仓 / 无矿 / 缺电 / 被禁用）钻机本来就不吸水，此刻的「没水」不算断水。
            // 少了这一条，满仓的钻机会在 5 秒后被误判成断水，额定值从 boost² 掉到 1 倍。
            st.dryStreak = 0;
        }else if(st.watered && ++st.dryStreak >= DRY_LATCH_SCANS){
            st.watered = false;
            st.dryStreak = 0;
        }

        return st;
    }

    /** 丢弃太久没被扫到的钻机状态（拆掉或移出选区的钻机不必一直留着）。 */
    private void pruneStates(){
        stale.clear();
        for(IntMap.Entry<DrillState> e : states){
            if(scanTick - e.value.seenAt > PRUNE_SCANS) stale.add(e.key);
        }
        for(int i = 0; i < stale.size; i++) states.remove(stale.get(i));
    }

    /**
     * 每帧调用一次：把每台钻机**本帧真实的产出量**记进它的 0.3 秒滑窗。
     *
     * <p>产出量直接来自**游戏的进度字段** {@code DrillBuild.progress}（public）：
     * 游戏每帧执行 {@code progress += delta() × dominantItems × speed × warmup}，
     * 达到 {@code delay} 就产出并 {@code progress %= delay}。所以本帧产出件数就是
     * {@code Δprogress / delay}（回绕时补回一个 delay）。这样数的是**游戏自己累加的量**，
     * 于是四种停机情形**自动归零**，不需要额外的停机判定：
     *
     * <ul>
     *   <li>被逻辑禁用 → {@code Building.update()} 跳过 {@code updateTile()} → progress 不动；</li>
     *   <li>无矿 → {@code updateTile()} 提前 return → 不动；</li>
     *   <li>满仓 / 缺电 → 走 else 分支、不累加 progress → 不动；</li>
     *   <li>超速 / 预热 / 供水 → 都已经包含在 progress 的增量里。</li>
     * </ul>
     *
     * <p>本帧产出先累进当前小片；小片满 {@link #SLICE_FRAMES}（0.05 秒）就封片写入环形窗口，
     * 窗口共 {@link #WINDOW_SLICES} 片（0.3 秒）。
     *
     * <p><b>唯一的边界</b>：一帧内产出 ≥2 件时（约 ≥60 件/秒）回绕无法精确还原，
     * 这类钻机回退用 {@code lastDrillSpeed} 的积分（两者在正常情况下代数等价）。
     * 回退分支仍需要停机判定，因为 {@code lastDrillSpeed} 在被禁用 / 无矿时会留旧值。
     */
    public void accumulate(){
        float dt = Time.delta;
        if(dt <= 0f) return;   // 游戏暂停 / 未推进

        for(int i = 0; i < stats.size; i++){
            Stat s = stats.get(i);
            DrillBuild b = s.build;
            DrillState st = states.get(b.id);
            if(st == null) continue;

            float delay = s.item == null ? 0f : s.drill.getDrillTime(s.item);
            float produced;

            if(60f * b.lastDrillSpeed * b.timeScale() >= FAST_DRILL_RATE){
                // 快钻：一帧可能产出多件，progress 回绕还原不准 → 用积分口径
                produced = stallReason(b) == null ? b.lastDrillSpeed * dt * b.timeScale() : 0f;
            }else if(delay > 0f && s.oreCount > 0){
                float d = b.progress - st.prevProgress;
                if(d < 0f) d += delay;                       // 产出过 → progress %= delay
                produced = Mathf.clamp(d, 0f, delay) / delay; // 件数（可为小数，长期积分精确）
            }else{
                produced = 0f;
            }

            st.prevProgress = b.progress;
            st.hasProgress = true;

            // 累进当前小片；满 0.05 秒（3 帧）就封片写入环形窗口
            st.sliceAmount += produced;
            st.sliceTime += dt;
            if(st.sliceTime >= SLICE_FRAMES){
                st.sliceAmounts[st.sliceIndex] = st.sliceAmount;
                st.sliceDurations[st.sliceIndex] = st.sliceTime;
                st.sliceIndex = (st.sliceIndex + 1) % WINDOW_SLICES;
                st.sliceAmount = 0f;
                st.sliceTime = 0f;
            }
        }
    }

    /**
     * 该钻机**当前 0.3 秒滑动窗口**的平均速率（个/秒）——即「窗口内速度和 ÷ 窗口时长」。
     *
     * <p>窗口由 {@link #WINDOW_SLICES} 个 0.05 秒小片组成，每片记下产出量与实际时长；
     * 求和后除以**实际总时长**（60fps 稳定时即 0.3 秒）。
     * 正在进行中的那一片也计入，所以窗口是连续滑动的、不会每 0.05 秒跳一次。
     */
    private float windowRate(DrillState st){
        float amount = st.sliceAmount, duration = st.sliceTime;
        for(int i = 0; i < WINDOW_SLICES; i++){
            amount += st.sliceAmounts[i];
            duration += st.sliceDurations[i];
        }
        return duration > 0f ? 60f * amount / duration : 0f;
    }

    /** 清空所有窗口累加器（重新开始框选时调用，避免把上一次的残留算进新的平均）。 */
    public void resetAccumulators(){
        for(IntMap.Entry<DrillState> e : states){
            DrillState st = e.value;
            // 0.3 秒滑窗整体清空
            for(int i = 0; i < WINDOW_SLICES; i++){
                st.sliceAmounts[i] = 0f;
                st.sliceDurations[i] = 0f;
            }
            st.sliceAmount = 0f;
            st.sliceTime = 0f;
            st.sliceIndex = 0;
            st.hasProgress = false;   // 进度基准也作废，下一帧重新取
        }
    }

    private Stat compute(DrillBuild b, DrillState st){
        Stat s = new Stat();
        s.build = b;
        s.drill = (Drill)b.block;
        s.item = b.dominantItem;
        s.oreCount = b.dominantItems;

        // 当前实际生效的加水倍率，仅用于内部参考；平方的理由见 theoreticalNet
        float ratio = Mathf.lerp(0f, 1f, b.optionalEfficiency);
        float liqBase = 1f + (s.drill.liquidBoostIntensity - 1f) * ratio;
        s.liquidMul = liqBase * liqBase;

        // ---- 净输出 = 额定理论值（定值，不含超速）----
        // 只由「钻机配置 + 覆盖矿格数 + 接水标志」决定，不读 lastDrillSpeed / efficiency / warmup
        // 这些每帧都在动的量，所以时断时动的钻机也不会让它跳。
        s.maxNet = theoreticalNet(s, st.watered);

        // ---- 实际输出 ----
        // 两种口径都由「取平均值」设置决定（见 averageActual）：
        //   平均：**0.3 秒滑窗**（每 0.05 秒一片）= 窗口内产出量 ÷ 窗口实际时长
        //   瞬时：60 × lastDrillSpeed × timeScale（与游戏速度条同口径，不摊停机时间）
        if(averageActual){
            s.actual = windowRate(st);
        }else{
            // 瞬时口径也要自己归零：被禁用 / 无矿时 lastDrillSpeed 会「留旧值」
            s.actual = stallReason(b) == null ? 60f * b.lastDrillSpeed * b.timeScale() : 0f;
        }
        // 停机原因用滞回后的值：只影响「计入 / 不计入」，不影响净输出数值
        s.stall = st.stall;
        return s;
    }

    /**
     * 额定理论产出（个/秒，不含超速）：{@code 60 × boost² × dominantItems / getDrillTime(item)}。
     *
     * <p><b>为什么是平方</b>：游戏 `updateTile()` 里产出速率是 `speed × warmup`，
     * 而预热值是 `approachDelta(warmup, speed, …)` —— **收敛到 `speed` 而不是 1**，
     * 于是稳态倍率就是 `speed²`。满水满效率时 `speed = liquidBoostIntensity`，
     * 实际产出即 `liquidBoostIntensity²` 倍，与信息面板 `Drill.setStats()` 的加成说明一致。
     *
     * <p><b>为什么是一组定值</b>：只取「钻机配置 + 覆盖矿格数 + 接水标志」，
     * 不乘 {@code efficiency} / {@code warmup}、不读 {@code lastDrillSpeed}——
     * 那样只会把运行时的抖动带进「理论值」里。这三个输入都是慢变量：
     * 配置静态、{@code dominantItems} 只在邻近方块变化时重算、接水标志带 5 秒滞回。
     */
    private float theoreticalNet(Stat s, boolean watered){
        if(s.item == null || s.oreCount <= 0) return 0f;

        float delay = s.drill.getDrillTime(s.item);
        if(delay <= 0f) return 0f;

        float boost = s.drill.liquidBoostIntensity;
        float mul = watered ? boost * boost : 1f;
        return 60f * mul * s.oreCount / delay;
    }

    /**
     * 是否「接了水」（用于决定额定值里的加水加成）。
     *
     * <p><b>不能只看 {@code optionalEfficiency}</b>：它是**本帧实际消耗量**换算出来的，
     * 而满仓 / 被禁用时 {@code shouldConsume()} 为假、钻机根本不吸水，
     * 于是它也变成 0 —— 那并不代表没接水。曾经就是因为这个，满仓的钻机会被误判成「断水」，
     * 额定值从 `boost²` 掉到 1 倍，净输出跟着变（违反「是定值」）。
     *
     * <p>所以先看**液罐里有没有液体**：它反映「接没接水」这个基础设施状态，
     * 且不依赖本帧是否在消耗（满仓时罐里照样存着水）。两个信号只要有一个成立就算接了水。
     */
    private static boolean watered(DrillBuild b){
        return (b.liquids != null && b.liquids.currentAmount() > 0.0001f) || b.optionalEfficiency > 0.0001f;
    }

    /**
     * 这台钻机的理论最大产出是否计入净输出。
     *
     * <p>只对「电力未连接」与「容量已满」两类停机受开关控制：
     * 其余情况（未停机 / 被禁用 / 无矿）一律按既有口径处理。
     * 无矿的 {@code maxNet} 本身为 0，被禁用则仍保留理论值，与 v1.0 行为一致。
     */
    private boolean countsTowardNet(Stat s){
        if(s.stall == null) return true;
        return switch(s.stall){
            case "nopower" -> netIncludeNoPower;
            case "full" -> netIncludeFull;
            default -> true;
        };
    }

    /** 判定停机原因，未停机返回 null。 */
    private static String stallReason(DrillBuild b){
        if(!b.enabled) return "disabled";
        if(b.dominantItem == null || b.dominantItems <= 0) return "noore";
        if(b.items.total() >= b.block.itemCapacity) return "full";
        if(b.efficiency <= 0.0001f) return "nopower";
        return null;
    }

    // ------------------------------------------------------------- 汇总读取

    /** 加水倍率的平均值（仅统计未停机的钻机）。 */
    public float avgLiquidMul(){
        return activeCount == 0 ? 0f : sumLiquidMul / activeCount;
    }

    /** 电力效率的平均值（仅统计未停机的钻机）。 */
    public float avgEfficiency(){
        return activeCount == 0 ? 0f : sumEfficiency / activeCount;
    }

    /** 实际 / 净。超速生效时大于 1。 */
    public float ratio(){
        return totalNet <= 0f ? 0f : totalActual / totalNet;
    }

    public String fixedNet(){
        return Strings.fixed(totalNet, 2);
    }

    public String fixedActual(){
        return Strings.fixed(totalActual, 2);
    }

    /** 按实际产出降序排列的副本。 */
    public Seq<Stat> sortedByActual(){
        Seq<Stat> copy = new Seq<>(stats);
        copy.sort((a, b) -> Float.compare(b.actual, a.actual));
        return copy;
    }

    public boolean isEmpty(){
        return stats.isEmpty();
    }
}
