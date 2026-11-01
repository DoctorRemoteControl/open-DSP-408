package de.drremote.dsp408controller.runtime;

import java.util.function.Consumer;

import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.state.ChannelState;

/** Owns gain and write-through mute operations. */
final class DspLevelCommands {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;
    private final DspTransactionCoordinator transactions;
    private final DspSettingsCache settingsCache;
    private final Consumer<String> log;

    DspLevelCommands(
            DspController controller,
            DspRuntimeDtoMapper dtoMapper,
            DspTransactionCoordinator transactions,
            DspSettingsCache settingsCache,
            Consumer<String> log
    ) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
        this.transactions = transactions;
        this.settingsCache = settingsCache;
        this.log = log;
    }

    ChannelDto setGain(DspChannel channel, double db) throws Exception {
        transactions.withMetersPaused(() -> {
            writeGainAndUpdateCache(channel, db, "api gain tx repeated");
            return null;
        });
        return dtoMapper.channel(controller.state().channel(channel));
    }

    ChannelDto setMute(String channelId, boolean muted) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        ChannelState currentState = controller.state().channel(channel);
        if (controller.isConnected()
                && settingsCache.status().ready()
                && currentState != null
                && currentState.muteConfirmedFromDevice()
                && Boolean.valueOf(muted).equals(currentState.muted())) {
            log.accept("Mute already confirmed for " + channel.displayName()
                    + ": muted=" + muted);
            return dtoMapper.channel(currentState);
        }

        if (!controller.isConnected()) {
            throw new IllegalStateException("DSP ist nicht verbunden.");
        }

        transactions.withMetersPaused(() -> {
            controller.setMute(channel, muted);
            return null;
        });
        log.accept("Mute write accepted for " + channel.displayName()
                + ": muted=" + muted + "; no automatic parameter-block readback.");
        return dtoMapper.channel(controller.state().channel(channel));
    }

    void writeGainAndUpdateCache(
            DspChannel channel,
            double requestedDb,
            String source
    ) throws Exception {
        int gainRaw = DspCodecRegistry.gain().doubleToRaw(requestedDb);
        double normalizedDb = DspCodecRegistry.gain().rawToDouble(gainRaw);
        controller.setGain(channel, normalizedDb);
        controller.state().markGain(channel, normalizedDb, source);
        Fir408WriteVerifier.GainLocation location = Fir408WriteVerifier.gainLocation(channel);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.dataOffset(), gainRaw);
    }
}
