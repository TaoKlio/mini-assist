import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 只读诊断工具：解析 Mindustry 的 settings.bin（Arc Settings 的私有二进制格式），
 * 打印全部键值，或只打印匹配前缀的键。
 *
 * 格式（arc.Settings#saveValues / #loadValues，v160）：
 *   int   count
 *   重复 count 次：
 *     UTF   key
 *     byte  type   0=boolean 1=int 2=long 3=float 4=String 5=byte[]
 *     value（按 type 读取）
 *
 * 用法：java DumpSettings.java <settings.bin> [keyPrefix]
 */
public class DumpSettings {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("用法: java DumpSettings.java <settings.bin> [keyPrefix]");
            return;
        }
        String prefix = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        TreeMap<String, String> map = new TreeMap<>();
        int count;
        try (DataInputStream in = new DataInputStream(new FileInputStream(args[0]))) {
            count = in.readInt();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                int type = in.readByte();
                String value;
                switch (type) {
                    case 0 -> value = Boolean.toString(in.readBoolean());
                    case 1 -> value = Integer.toString(in.readInt());
                    case 2 -> value = Long.toString(in.readLong());
                    case 3 -> value = Float.toString(in.readFloat());
                    case 4 -> value = in.readUTF();
                    case 5 -> {
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);
                        value = "<bytes:" + len + ">";
                    }
                    default -> throw new IOException("未知类型 " + type + " (键 " + key + ")");
                }
                map.put(key, "[" + type + "] " + value);
            }
        }
        System.out.println("总键数: " + count + "，解析成功: " + map.size());
        int shown = 0;
        for (var e : map.entrySet()) {
            if (prefix == null || e.getKey().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                System.out.println("  " + e.getKey() + " = " + e.getValue());
                shown++;
            }
        }
        System.out.println("打印: " + shown);
    }
}
