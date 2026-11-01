package de.drremote.dsp408controller.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DspDeviceDefinitionDto;
import de.drremote.dsp408.api.DspDeviceStorageException;

/** Strict versioned storage. A failed write never truncates the last committed file. */
final class DspDeviceRegistryStore {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "devices");
    private static final Set<String> DEVICE_FIELDS = Set.of("id", "host", "port", "library", "autoConnect");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path path;
    private byte[] committedBytes;

    DspDeviceRegistryStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    Path path() { return path; }

    List<DspDeviceDefinitionDto> load() {
        try {
            if (!Files.exists(path)) {
                save(List.of());
                return List.of();
            }
            byte[] bytes = Files.readAllBytes(path);
            JsonNode root = mapper.readTree(bytes);
            requireFields(root, ROOT_FIELDS);
            JsonNode version = root.get("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1
                    || !root.get("devices").isArray()) {
                throw new IllegalArgumentException("Unsupported or invalid device registry schema");
            }
            List<DspDeviceDefinitionDto> definitions = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> endpoints = new HashSet<>();
            for (JsonNode device : root.get("devices")) {
                requireFields(device, DEVICE_FIELDS);
                if (!device.get("id").isTextual() || !device.get("host").isTextual()
                        || !device.get("library").isTextual() || !device.get("autoConnect").isBoolean()
                        || !device.get("port").isIntegralNumber() || !device.get("port").canConvertToInt()) {
                    throw new IllegalArgumentException("Invalid device registry definition types");
                }
                var definition = new DspDeviceDefinitionDto(device.get("id").textValue(),
                        device.get("host").textValue(), device.get("port").intValue(),
                        device.get("library").textValue(), device.get("autoConnect").booleanValue());
                if (!ids.add(definition.id()) || !endpoints.add(endpointKey(definition))) {
                    throw new IllegalArgumentException("Duplicate DSP id or endpoint in device registry");
                }
                definitions.add(definition);
            }
            committedBytes = bytes;
            // Test the destination directory without modifying the valid registry.
            Path probe = Files.createTempFile(path.getParent(), ".dsp-registry-probe-", ".tmp");
            Files.delete(probe);
            return List.copyOf(definitions);
        } catch (IOException | IllegalArgumentException error) {
            throw failure("read", error);
        }
    }

    void save(List<DspDeviceDefinitionDto> devices) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            if (committedBytes == null ? Files.exists(path)
                    : !Arrays.equals(committedBytes, Files.readAllBytes(path))) {
                throw new IOException("Registry storage changed outside this runtime; refusing to overwrite it");
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new StoredRegistry(1, devices));
            temporary = Files.createTempFile(path.getParent(), ".dsp-registry-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committedBytes = bytes;
        } catch (IOException error) {
            throw failure("write", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* preserve original failure */ }
            }
        }
    }

    static String endpointKey(DspDeviceDefinitionDto definition) {
        return definition.host() + "|" + definition.port();
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Registry object required");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Unexpected or missing registry fields");
    }

    private DspDeviceStorageException failure(String operation, Exception cause) {
        return new DspDeviceStorageException("Cannot " + operation + " DSP device registry " + path + ": " + cause.getMessage(), cause);
    }

    private record StoredRegistry(int schemaVersion, List<DspDeviceDefinitionDto> devices) { }
}
