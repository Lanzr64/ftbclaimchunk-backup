package de.melanx.simplebackups;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimedMcaSelectorTest {
    private static ClaimedMcaSelector.Claim claim(String dimension, int x, int z) {
        return new ClaimedMcaSelector.Claim(new ResourceLocation(dimension), x, z);
    }

    @Test
    void selectsWholeMcaAndSidecarsAcrossDimensionsWithNegativeCoordinates() throws Exception {
        ClaimedMcaSelector selector = ClaimedMcaSelector.fromClaims(List.of(
                claim("minecraft:overworld", -1, 31),
                claim("minecraft:the_nether", 64, -33)));

        assertTrue(selector.includes(Path.of("ftbchunks", "team.snbt")));
        assertTrue(selector.includes(Path.of("region", "r.-1.0.mca")));
        assertTrue(selector.includes(Path.of("entities", "r.-1.0.mca")));
        assertTrue(selector.includes(Path.of("poi", "c.-1.31.mcc")));
        assertTrue(selector.includes(Path.of("DIM-1", "region", "r.2.-2.mca")));
        assertFalse(selector.includes(Path.of("region", "r.0.0.mca")));
        assertFalse(selector.includes(Path.of("DIM1", "region", "r.2.-2.mca")));
        assertFalse(selector.includes(Path.of("level.dat")));
    }

    @Test
    void fingerprintChangesOnlyWhenSelectedMcaRegionsChange() throws Exception {
        var first = ClaimedMcaSelector.fromClaims(List.of(claim("minecraft:overworld", 0, 0),
                claim("minecraft:the_end", -1, 0)));
        var reordered = ClaimedMcaSelector.fromClaims(List.of(claim("minecraft:the_end", -1, 0),
                claim("minecraft:overworld", 31, 31)));
        var expanded = ClaimedMcaSelector.fromClaims(List.of(claim("minecraft:overworld", 32, 0),
                claim("minecraft:the_end", -1, 0)));

        assertEquals(first.scopeFingerprint(), reordered.scopeFingerprint());
        assertNotEquals(first.scopeFingerprint(), expanded.scopeFingerprint());
        assertThrows(IOException.class, () -> ClaimedMcaSelector.fromClaims(List.of()));
    }
}
