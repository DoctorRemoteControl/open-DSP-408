package de.drremote.dsp408controller.runtime;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.drremote.dsp408.api.CapabilitiesDto;
import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.ControlStateDto;
import de.drremote.dsp408.api.SettingsCacheDto;

/** Short RAM-only collector lock, independent of transaction admission and device I/O. */
final class DspControlState {
    private final String instanceId = UUID.randomUUID().toString();
    private final Supplier<String> dspId;
    private final BooleanSupplier connected;
    private final Supplier<List<ChannelDto>> channels;
    private final Supplier<CapabilitiesDto> capabilities;
    private final Supplier<SettingsCacheDto> settingsCache;
    private ControlStateDto previous;
    private long revision;

    DspControlState(Supplier<String> dspId, BooleanSupplier connected,
            Supplier<List<ChannelDto>> channels, Supplier<CapabilitiesDto> capabilities,
            Supplier<SettingsCacheDto> settingsCache) {
        this.dspId = dspId;
        this.connected = connected;
        this.channels = channels;
        this.capabilities = capabilities;
        this.settingsCache = settingsCache;
    }

    /** Every possibly transmitted write consumes its condition, including equal values. */
    synchronized void consumeRevision() {
        revision++;
        previous = null;
    }

    synchronized ControlStateDto snapshot() {
        var current = new ControlStateDto(dspId.get(), instanceId, revision, connected.getAsBoolean(),
                channels.get(), capabilities.get(), settingsCache.get());
        if (previous != null && !previous.equals(current)) {
            revision++;
            current = new ControlStateDto(current.dspId(), instanceId, revision, current.connected(),
                    current.channels(), current.capabilities(), current.settingsCache());
        }
        previous = current;
        return current;
    }
}
