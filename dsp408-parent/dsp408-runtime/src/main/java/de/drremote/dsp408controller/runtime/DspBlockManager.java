package de.drremote.dsp408controller.runtime;

import java.util.List;

import de.drremote.dsp408.api.BlockDto;
import de.drremote.dsp408.api.StateDto;
import de.drremote.dsp408controller.core.service.DspController;

/** Owns cached parameter-block access and authoritative refresh operations. */
final class DspBlockManager {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;
    private final DspTransactionCoordinator transactions;
    private final DspSettingsCache settingsCache;

    DspBlockManager(
            DspController controller,
            DspRuntimeDtoMapper dtoMapper,
            DspTransactionCoordinator transactions,
            DspSettingsCache settingsCache
    ) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
        this.transactions = transactions;
        this.settingsCache = settingsCache;
    }

    List<String> cachedBlockIndices() {
        return controller.state().cachedBlockIndices().stream()
                .map(DspRuntimeDtoMapper::formatIndexHex)
                .toList();
    }

    BlockDto cachedBlock(String blockIndexHex) {
        int index = parseHexIndex(blockIndexHex);
        return dtoMapper.block(index, controller.state().getBlock(index));
    }

    BlockDto read(String blockIndexHex) throws Exception {
        int index = parseHexIndex(blockIndexHex);
        byte[] data = transactions.readFreshParameterBlock(index);
        settingsCache.promoteComplete();
        return dtoMapper.block(index, data);
    }

    StateDto scan() throws Exception {
        settingsCache.synchronize("explicit refresh");
        return dtoMapper.state();
    }

    private int parseHexIndex(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Block index is missing");
        }
        if (!cleaned.matches("(?i)[0-9a-f]{1,2}")) {
            throw new IllegalArgumentException(
                    "Block index must be a hex value in range 00.."
                            + DspRuntimeDtoMapper.formatIndexHex(controller.maxParameterBlockIndex())
            );
        }
        int index = Integer.parseInt(cleaned, 16);
        int max = controller.maxParameterBlockIndex();
        if (index < 0x00 || index > max) {
            throw new IllegalArgumentException(
                    "Block index must be in range 00.." + DspRuntimeDtoMapper.formatIndexHex(max)
            );
        }
        return index;
    }
}
