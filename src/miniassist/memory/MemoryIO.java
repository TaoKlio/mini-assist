package miniassist.memory;

import mindustry.world.blocks.logic.MemoryBlock;

import java.lang.reflect.*;

public class MemoryIO{
    private static final Field objF, numF;
    private static final Object sentinel; 

    static{
        try{
            Class<?> type = MemoryBlock.MemoryBuild.class;
            objF = type.getDeclaredField("objectMemory");
            numF = type.getDeclaredField("numberMemory");
            Field s = type.getDeclaredField("sentinel");
            objF.setAccessible(true);
            numF.setAccessible(true);
            s.setAccessible(true);
            sentinel = s.get(null);
        }catch(Exception e){
            throw new RuntimeException("[memory-access] 初始化反射失败，游戏版本可能改了 MemoryBlock", e);
        }
    }

    public record Result(boolean ok, int written, int skippedObject, int skippedBad){}

    /** 返回该内存块的槽位容量（numberMemory 的长度） */
    public static int capacity(MemoryBlock.MemoryBuild b){
        return numbers(b).length;
    }

    /** 读取全部槽位并编码为文本写入剪贴板 */
    public static String encodeAll(MemoryBlock.MemoryBuild b){
        Object[] objs = objects(b);
        double[] nums = numbers(b);
        StringBuilder sb = new StringBuilder(objs.length * 8 + 64);
        sb.append("#mindustry-memory v1\n#capacity ").append(objs.length).append('\n');
        for(int i = 0; i < objs.length; i++){
            sb.append(i).append(' ');
            Object o = objs[i];
            if(o == sentinel){
                sb.append(nums[i]); // 数字槽
            }else{
                sb.append("<object:").append(o == null ? "null" : o.getClass().getSimpleName()).append('>');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 解析剪贴板文本并写入数字槽；对象槽/坏行/越界地址跳过 */
    public static Result decodeAndWrite(String text, MemoryBlock.MemoryBuild b){
        if(text == null) return new Result(false, 0, 0, 0);
        Object[] objs = objects(b);
        double[] nums = numbers(b);
        int cap = objs.length, written = 0, skippedObj = 0, skippedBad = 0;

        for(String line : text.split("\n")){
            line = line.trim();
            if(line.isEmpty() || line.startsWith("#")) continue;

            int sp = line.indexOf(' ');
            if(sp <= 0){ skippedBad++; continue; }

            int addr;
            try{
                addr = Integer.parseInt(line.substring(0, sp).trim());
            }catch(Exception e){ skippedBad++; continue; }

            String raw = line.substring(sp + 1).trim();
            if(raw.startsWith("<object")){ skippedObj++; continue; }

            double val;
            try{
                val = Double.parseDouble(raw);
            }catch(Exception e){ skippedBad++; continue; }

            if(addr < 0 || addr >= cap) continue; // 容量不匹配（如 512->32）：忽略越界
            objs[addr] = sentinel;
            nums[addr] = val;
            written++;
        }
        return new Result(written + skippedObj > 0, written, skippedObj, skippedBad);
    }

    private static Object[] objects(MemoryBlock.MemoryBuild b){
        try{ return (Object[])objF.get(b); }
        catch(Exception e){ throw new RuntimeException(e); }
    }

    private static double[] numbers(MemoryBlock.MemoryBuild b){
        try{ return (double[])numF.get(b); }
        catch(Exception e){ throw new RuntimeException(e); }
    }
}