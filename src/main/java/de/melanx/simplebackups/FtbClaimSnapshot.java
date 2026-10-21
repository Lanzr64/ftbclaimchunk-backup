package de.melanx.simplebackups;

import dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads FTB Chunks' live claim map only on the Minecraft server thread. */
public final class FtbClaimSnapshot {
    private FtbClaimSnapshot() {}

    public static ClaimedMcaSelector capture(MinecraftServer server) throws IOException {
        if (!server.isSameThread()) {
            throw new IOException("FTB Chunks claims must be captured on the server thread");
        }
        ClaimedChunkManagerImpl manager = ClaimedChunkManagerImpl.getInstance();
        if (manager == null || manager.getMinecraftServer() != server) {
            throw new IOException("FTB Chunks claim manager is not ready for this server");
        }

        List<ClaimedMcaSelector.Claim> claims = new ArrayList<>();
        manager.getAllClaimedChunks().forEach(chunk -> {
            var pos = chunk.getPos();
            claims.add(new ClaimedMcaSelector.Claim(pos.dimension().location(), pos.x(), pos.z()));
        });
        return ClaimedMcaSelector.fromClaims(claims);
    }
}
