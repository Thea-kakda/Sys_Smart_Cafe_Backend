import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.CRC32;

// Replicates Flyway's ChecksumCalculator: CRC32 over each line (terminators stripped, BOM removed), UTF-8.
public class _v2_FlywayCrc {
    public static void main(String[] args) throws Exception {
        for (String f : args) {
            CRC32 crc = new CRC32();
            try (BufferedReader r = Files.newBufferedReader(Path.of(f), StandardCharsets.UTF_8)) {
                String line; boolean first = true;
                while ((line = r.readLine()) != null) {
                    if (first && !line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                    first = false;
                    crc.update(line.getBytes(StandardCharsets.UTF_8));
                }
            }
            System.out.println((int) crc.getValue() + "  " + Path.of(f).getFileName());
        }
    }
}
