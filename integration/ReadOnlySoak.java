import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.service.DspController;

/** Explicit-target read-only validation; the parent proxy enforces allowed wire payloads. */
public class ReadOnlySoak {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long duration = Long.parseLong(args[1]);
        long reconnectSeconds = Long.parseLong(args[2]);
        Path output = Path.of(args[3]);
        long started = System.nanoTime();
        long deadline = started + duration * 1_000_000_000L;
        DspController controller = new DspController();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("started_utc", Instant.now().toString());
        long requests = 0, missed = 0, reconnects = 0;
        List<Map<String, Object>> resources = new ArrayList<>();
        boolean success = false;
        try {
            connect(controller, port);
            result.put("firmware", controller.deviceVersion());
            Map<String, String> before = dump(controller);
            save(output.resolve("before.json"), before);
            String digest = digest(before);
            result.put("configuration_sha256", digest);
            result.put("blocks", before.size());
            long nextDump = System.nanoTime() + 60_000_000_000L;
            long nextReconnect = System.nanoTime() + reconnectSeconds * 1_000_000_000L;
            long nextSample = System.nanoTime();
            while (System.nanoTime() < deadline) {
                if (!controller.isConnected()) throw new IllegalStateException("DSP session lost during soak");
                Long prior = controller.state().meterState().lastRuntimePayloadObservedAtEpochMs();
                controller.requestRuntimeMeters();
                requests++;
                Long current = controller.state().meterState().lastRuntimePayloadObservedAtEpochMs();
                if (Objects.equals(prior, current)) missed++;
                long now = System.nanoTime();
                if (now >= nextDump) {
                    if (!before.equals(dump(controller))) throw new IllegalStateException("Read-only configuration changed");
                    nextDump = System.nanoTime() + 60_000_000_000L;
                }
                if (now >= nextSample) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("seconds", (now-started)/1_000_000_000L);
                    sample.put("requests", requests);
                    sample.put("missed", missed);
                    sample.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
                    sample.put("gc_collections", ManagementFactory.getGarbageCollectorMXBeans().stream()
                            .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum());
                    sample.put("heap_used", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    sample.put("owned_dsp_threads", Thread.getAllStackTraces().keySet().stream()
                            .filter(t -> t.getName().startsWith("dsp408-")).count());
                    resources.add(sample);
                    save(output.resolve("resources.json"), resources);
                    System.out.println("SOAK " + JSON.writeValueAsString(sample));
                    nextSample = now + 30_000_000_000L;
                }
                if (now >= nextReconnect) {
                    controller.close();
                    connect(controller, port);
                    reconnects++;
                    if (!before.equals(dump(controller))) throw new IllegalStateException("Configuration differs after reconnect");
                    nextReconnect = System.nanoTime() + reconnectSeconds * 1_000_000_000L;
                }
                Thread.sleep(200L);
            }
            Map<String, String> after = dump(controller);
            save(output.resolve("after.json"), after);
            result.put("after_sha256", digest(after));
            result.put("configuration_identical", before.equals(after));
            if (!before.equals(after)) throw new IllegalStateException("Final configuration differs");
            success = true;
        } catch (Exception error) {
            result.put("error", error.getClass().getName() + ": " + error.getMessage());
            error.printStackTrace();
        } finally {
            controller.close();
            result.put("success", success);
            result.put("elapsed_seconds", (System.nanoTime()-started)/1_000_000_000.0);
            result.put("meter_requests", requests);
            result.put("missing_meter_samples", missed);
            result.put("reconnects", reconnects);
            save(output.resolve("result.json"), result);
        }
        if (!success) System.exit(1);
    }

    private static void connect(DspController controller, int port) throws Exception {
        controller.setPreferredLibraryType("fir408");
        controller.connect(new DspConnectionConfig("127.0.0.1", port, 2_000, 1_200, 2_500, 5_000),
                message -> System.out.println(Instant.now() + " " + message));
    }

    private static Map<String, String> dump(DspController controller) throws Exception {
        Map<String, String> blocks = new LinkedHashMap<>();
        for (int index=0; index<=controller.maxParameterBlockIndex(); index++) {
            long revision = controller.state().blockRevision(index);
            controller.readParameterBlock(index);
            byte[] bytes = controller.state().getBlock(index);
            if (bytes == null || controller.state().blockRevision(index) <= revision)
                throw new IllegalStateException("No fresh block " + index);
            blocks.put(String.format("%02X", index), HEX.formatHex(bytes));
        }
        return blocks;
    }

    private static String digest(Map<String, String> blocks) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String value : blocks.values()) sha.update(HEX.parseHex(value));
        return HEX.formatHex(sha.digest());
    }

    private static void save(Path path, Object value) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}
