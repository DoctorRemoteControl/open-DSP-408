package de.drremote.dsp408controller.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.drremote.dsp408.api.DspDeviceConflictException;
import de.drremote.dsp408.api.DspDeviceDefinitionDto;
import de.drremote.dsp408.api.DspDeviceNotFoundException;
import de.drremote.dsp408.api.DspDeviceRegistrationDto;
import de.drremote.dsp408.api.DspDeviceRegistryDto;
import de.drremote.dsp408.api.DspInstanceDto;
import de.drremote.dsp408.api.DspService;

/** Serializes durable mutations and owns each concrete device runtime exactly once. */
final class DspManagedDeviceRegistry implements AutoCloseable {
    private final DspDeviceRegistryStore store;
    private volatile Map<String, Entry> devices = Map.of();
    private Dsp408Configuration configuration;
    private volatile boolean closed;

    DspManagedDeviceRegistry(Path path, Dsp408Configuration configuration) {
        this.store = new DspDeviceRegistryStore(path);
        this.configuration = configuration;
        List<DspDeviceDefinitionDto> definitions = store.load();
        Map<String, Entry> prepared = new LinkedHashMap<>();
        try {
            for (DspDeviceDefinitionDto definition : definitions) {
                prepared.put(definition.id(), prepare(definition));
            }
            devices = immutable(prepared);
        } catch (RuntimeException error) {
            prepared.values().forEach(entry -> entry.runtime().deactivate());
            closed = true;
            throw error;
        }
    }

    Path path() { return store.path(); }

    boolean isEmpty() { return devices.isEmpty(); }

    DspDeviceRegistryDto snapshot() {
        ensureOpen();
        return new DspDeviceRegistryDto("managed", true, definitions());
    }

    List<DspInstanceDto> instances() {
        ensureOpen();
        return devices.values().stream().map(entry -> entry.runtime().toInstanceDto(false)).toList();
    }

    DspService resolve(String id) {
        ensureOpen();
        if (id == null || id.isBlank()) {
            throw new DspDeviceConflictException("Select a registered DSP id; the managed registry has no default device");
        }
        String normalized = DspDeviceDefinitionDto.normalizeId(id);
        Entry entry = devices.get(normalized);
        if (entry == null) throw new DspDeviceNotFoundException("Unknown DSP id: " + id);
        return entry.runtime();
    }

    synchronized DspDeviceRegistrationDto register(DspDeviceDefinitionDto definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "DSP definition is required");
        Entry existing = devices.get(definition.id());
        if (existing != null) {
            if (existing.definition().equals(definition)) return new DspDeviceRegistrationDto(definition, false);
            throw new DspDeviceConflictException("DSP id already has a different immutable definition: " + definition.id());
        }
        for (Entry entry : devices.values()) {
            if (DspDeviceRegistryStore.endpointKey(entry.definition()).equals(DspDeviceRegistryStore.endpointKey(definition))) {
                throw new DspDeviceConflictException("DSP endpoint already belongs to " + entry.definition().id());
            }
        }
        Entry candidate = prepare(definition);
        try {
            List<DspDeviceDefinitionDto> replacement = new ArrayList<>(definitions());
            replacement.add(definition);
            store.save(replacement);
        } catch (RuntimeException error) {
            candidate.runtime().deactivate();
            throw error;
        }
        Map<String, Entry> replacement = new LinkedHashMap<>(devices);
        replacement.put(definition.id(), candidate);
        devices = immutable(replacement);
        enableAutoConnect(candidate);
        return new DspDeviceRegistrationDto(definition, true);
    }

    synchronized void remove(String id) {
        ensureOpen();
        String normalized = DspDeviceDefinitionDto.normalizeId(id);
        Entry removed = devices.get(normalized);
        if (removed == null) throw new DspDeviceNotFoundException("Unknown DSP id: " + id);
        List<DspDeviceDefinitionDto> replacement = definitions().stream().filter(d -> !d.id().equals(normalized)).toList();
        store.save(replacement);
        Map<String, Entry> remaining = new LinkedHashMap<>(devices);
        remaining.remove(normalized);
        devices = immutable(remaining);
        removed.runtime().deactivate();
    }

    synchronized void startAutoConnections() {
        ensureOpen();
        devices.values().forEach(this::enableAutoConnect);
    }

    synchronized void updateConfiguration(Dsp408Configuration configuration) {
        ensureOpen();
        this.configuration = configuration;
        for (Entry entry : devices.values()) {
            entry.runtime().modified(DspDevicePool.childConfiguration(entry.definition(), configuration, entry.definition().autoConnect()));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Map<String, Entry> previous = devices;
        devices = Map.of();
        previous.values().forEach(entry -> entry.runtime().deactivate());
    }

    private List<DspDeviceDefinitionDto> definitions() {
        return devices.values().stream().map(Entry::definition).toList();
    }

    private Entry prepare(DspDeviceDefinitionDto definition) {
        DspRuntimeComponent child = new DspRuntimeComponent(false, true);
        try {
            child.activate(DspDevicePool.childConfiguration(definition, configuration, false));
            return new Entry(definition, child);
        } catch (RuntimeException error) {
            child.deactivate();
            throw error;
        }
    }

    private void enableAutoConnect(Entry entry) {
        if (entry.definition().autoConnect()) {
            entry.runtime().modified(DspDevicePool.childConfiguration(entry.definition(), configuration, true));
        }
    }

    private static Map<String, Entry> immutable(Map<String, Entry> entries) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("DSP device registry is closed");
    }

    private record Entry(DspDeviceDefinitionDto definition, DspRuntimeComponent runtime) { }
}
