import com.wgj.switchquery.ui.ResultRefiner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * 对比基准工具：把 Java 版 ResultRefiner 对 samples/*.txt 的输出写成 UTF-8 文本，
 * 格式与 Kotlin 版 parity 模式完全一致，可直接 diff。
 *
 * 用法：java -cp <classes> ParityDump <samplesDir> <outFile>
 */
public class ParityDump {

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null) throw new IllegalStateException("no samples: " + dir);
        Arrays.sort(files);
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            String type = name.contains("__") ? name.substring(0, name.indexOf("__")) : "unknown";
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            sb.append(name).append('|').append(type).append('|')
                    .append(esc(ResultRefiner.refine(type, raw))).append('\n');
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + args[1] + " (" + files.length + " cases)");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
    }
}
