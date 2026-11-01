package de.drremote.dsp408controller.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.drremote.dsp408.api.BlockDto;
import de.drremote.dsp408.api.PresetSnapshotDto;
import de.drremote.dsp408.api.PresetStoreResultDto;
import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.util.Hex;

/** Owns preset export, recall, store and readback confirmation. */
final class DspPresetManager {
    private static final long RECALL_SETTLE_MS = 1_500L;
    private static final long STORE_SETTLE_MS = 8_000L;

    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;
    private final DspTransactionCoordinator transactions;
    private final DspSettingsCache settingsCache;
    private final Supplier<String> dspId;
    private final Runnable invalidateAutoLimitUndo;
    private final Consumer<String> log;

    DspPresetManager(
            DspController controller,
            DspRuntimeDtoMapper dtoMapper,
            DspTransactionCoordinator transactions,
            DspSettingsCache settingsCache,
            Supplier<String> dspId,
            Runnable invalidateAutoLimitUndo,
            Consumer<String> log
    ) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
        this.transactions = transactions;
        this.settingsCache = settingsCache;
        this.dspId = dspId;
        this.invalidateAutoLimitUndo = invalidateAutoLimitUndo;
        this.log = log;
    }

    PresetSnapshotDto exportSnapshot() throws Exception {
        settingsCache.synchronize("preset JSON export");

        List<BlockDto> blocks = new ArrayList<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index <= controller.maxParameterBlockIndex(); index++) {
            byte[] data = controller.state().getBlock(index);
            if (data == null) {
                throw new IllegalStateException(
                        "Preset export is incomplete: parameter block "
                                + DspRuntimeDtoMapper.formatIndexHex(index) + " is missing."
                );
            }
            digest.update((byte) index);
            digest.update((byte) (data.length & 0xFF));
            digest.update(data);
            blocks.add(dtoMapper.block(index, data));
        }

        byte[] blockZero = controller.state().getBlock(0);
        return new PresetSnapshotDto(
                "fir408-preset-snapshot/1",
                dspId.get(),
                controller.libraryType(),
                System.currentTimeMillis(),
                decodeCurrentName(blockZero),
                Hex.format(digest.digest()).replace(" ", "").toLowerCase(Locale.ROOT),
                dtoMapper.state(),
                blocks
        );
    }

    RawTxDto load(String slotLabel) throws Exception {
        controller.loadPreset(slotLabel);
        invalidateAutoLimitUndo.run();
        Thread.sleep(RECALL_SETTLE_MS);
        settingsCache.synchronize("preset recall " + slotLabel);
        return dtoMapper.rawTx(DspProtocol.loadPreset(slotLabel));
    }

    RawTxDto load(int presetIndex) throws Exception {
        controller.loadPreset(presetIndex);
        invalidateAutoLimitUndo.run();
        Thread.sleep(RECALL_SETTLE_MS);
        settingsCache.synchronize("preset recall " + presetIndex);
        return dtoMapper.rawTx(DspProtocol.loadPreset(presetIndex));
    }

    PresetStoreResultDto store(String slotLabel, String name) throws Exception {
        int presetIndex = DspProtocol.parsePresetSlot(slotLabel);
        byte[] namePayload = DspProtocol.buildPresetName(name);
        byte[] storePayload = DspProtocol.storePreset(presetIndex);
        String normalizedName = decodeNamePayload(namePayload);

        String confirmedName = transactions.withMetersPaused(() -> {
            String readbackName = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                controller.setPresetName(normalizedName);
                controller.storePreset(presetIndex);
                controller.sendRaw(DspProtocol.keepAlive());
                // FIR408 writes the complete active configuration to flash and
                // remains silent for roughly eight seconds during that write.
                Thread.sleep(STORE_SETTLE_MS);
                byte[] blockZero = transactions.readFreshParameterBlock(0);
                readbackName = decodeCurrentName(blockZero);
                if (normalizedName.equals(readbackName)) {
                    return readbackName;
                }
                log.accept("Preset store block-0 readback mismatch for " + slotLabel
                        + " attempt " + attempt + ": expected=" + normalizedName
                        + " actual=" + readbackName);
            }
            return readbackName;
        });

        if (!normalizedName.equals(confirmedName)) {
            throw new IllegalStateException(
                    "Preset " + slotLabel
                            + " could not be confirmed after storing; read back name was "
                            + (confirmedName == null ? "missing" : confirmedName) + "."
            );
        }

        return new PresetStoreResultDto(
                String.format(Locale.ROOT, "U%02d", presetIndex),
                presetIndex,
                normalizedName,
                System.currentTimeMillis(),
                true,
                confirmedName,
                List.of(dtoMapper.rawTx(namePayload), dtoMapper.rawTx(storePayload))
        );
    }

    RawTxDto readName(int presetIndex) throws Exception {
        controller.readPresetName(presetIndex);
        return dtoMapper.rawTx(DspProtocol.readPresetName(presetIndex));
    }

    static String decodeCurrentName(byte[] blockZero) {
        if (blockZero == null || blockZero.length <= 2) {
            return null;
        }
        int end = Math.min(blockZero.length, 16);
        while (end > 2 && (blockZero[end - 1] == 0x00 || blockZero[end - 1] == 0x20)) {
            end--;
        }
        String value = new String(blockZero, 2, end - 2, StandardCharsets.US_ASCII);
        return value.isBlank() ? null : value;
    }

    static String decodeNamePayload(byte[] payload) {
        int end = payload.length;
        while (end > 4 && payload[end - 1] == 0x20) {
            end--;
        }
        return new String(payload, 4, end - 4, StandardCharsets.US_ASCII);
    }
}
