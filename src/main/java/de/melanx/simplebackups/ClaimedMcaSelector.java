package de.melanx.simplebackups;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable selection of complete MCA files containing FTB Chunks claims. */
public final class ClaimedMcaSelector {
    private static final Pattern MCA = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Pattern MCC = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final String[] REGION_DIRECTORIES = {"region", "entities", "poi"};
    private static final Path CLAIM_DIRECTORY = Path.of("ftbchunks");

    private final Map<Path, Set<RegionPos>> regionsByDirectory;
    private final String scopeFingerprint;

    private ClaimedMcaSelector(Map<Path, Set<RegionPos>> regionsByDirectory, String scopeFingerprint) {
        this.regionsByDirectory = regionsByDirectory;
        this.scopeFingerprint = scopeFingerprint;
    }

    public record Claim(ResourceLocation dimension, int chunkX, int chunkZ) {}

    public static ClaimedMcaSelector fromClaims(Collection<Claim> claims) throws IOException {
        if (claims.isEmpty()) {
            throw new IOException("No FTB Chunks claims are loaded; refusing to create an empty backup");
        }

        Map<Path, Set<RegionPos>> selected = new HashMap<>();
        for (Claim claim : claims) {
            Path dimensionDirectory = dimensionDirectory(claim.dimension());
            if (dimensionDirectory.isAbsolute() || dimensionDirectory.normalize().startsWith("..")) {
                throw new IOException("Invalid claim dimension path: " + claim.dimension());
            }
            RegionPos region = new RegionPos(Math.floorDiv(claim.chunkX(), 32), Math.floorDiv(claim.chunkZ(), 32));
            for (String kind : REGION_DIRECTORIES) {
                selected.computeIfAbsent(dimensionDirectory.resolve(kind), ignored -> new HashSet<>()).add(region);
            }
        }

        Map<Path, Set<RegionPos>> immutable = new HashMap<>();
        selected.forEach((directory, regions) -> immutable.put(directory, Set.copyOf(regions)));
        Map<Path, Set<RegionPos>> snapshot = Map.copyOf(immutable);
        return new ClaimedMcaSelector(snapshot, fingerprint(snapshot));
    }

    public String scopeFingerprint() {
        return this.scopeFingerprint;
    }

    private static String fingerprint(Map<Path, Set<RegionPos>> selected) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            selected.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()
                            .map(region -> entry.getKey().toString().replace('\\', '/') + "/" + region.x() + "/" + region.z()))
                    .sorted()
                    .forEach(value -> {
                        digest.update(value.getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public boolean includes(Path relativePath) {
        if (this.isClaimFile(relativePath)) {
            return true;
        }
        Path directory = relativePath.getParent();
        if (directory == null) {
            return false;
        }
        Set<RegionPos> regions = this.regionsByDirectory.get(directory);
        if (regions == null) {
            return false;
        }
        String name = relativePath.getFileName().toString();
        try {
            Matcher mca = MCA.matcher(name);
            if (mca.matches()) {
                return regions.contains(new RegionPos(Integer.parseInt(mca.group(1)), Integer.parseInt(mca.group(2))));
            }
            Matcher mcc = MCC.matcher(name);
            if (mcc.matches()) {
                return regions.contains(new RegionPos(Math.floorDiv(Integer.parseInt(mcc.group(1)), 32),
                        Math.floorDiv(Integer.parseInt(mcc.group(2)), 32)));
            }
        } catch (NumberFormatException ignored) {
            // A filename outside the integer coordinate range is not a Minecraft region file.
        }
        return false;
    }

    public boolean isClaimFile(Path relativePath) {
        return CLAIM_DIRECTORY.equals(relativePath.getParent())
                && relativePath.getFileName().toString().endsWith(".snbt");
    }

    private static Path dimensionDirectory(ResourceLocation dimension) {
        if (dimension.getNamespace().equals("minecraft") && dimension.getPath().equals("overworld")) {
            return Path.of("");
        }
        if (dimension.getNamespace().equals("minecraft") && dimension.getPath().equals("the_nether")) {
            return Path.of("DIM-1");
        }
        if (dimension.getNamespace().equals("minecraft") && dimension.getPath().equals("the_end")) {
            return Path.of("DIM1");
        }
        return Path.of("dimensions", dimension.getNamespace(), dimension.getPath());
    }

    private record RegionPos(int x, int z) {}
}
