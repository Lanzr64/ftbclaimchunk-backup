package de.melanx.simplebackups;

import de.melanx.simplebackups.compat.CherishedWorldsCompat;
import de.melanx.simplebackups.compat.Mc2DiscordCompat;
import de.melanx.simplebackups.config.BackupType;
import de.melanx.simplebackups.config.CommonConfig;
import de.melanx.simplebackups.config.ServerConfig;
import de.melanx.simplebackups.exception.NotEnoughDiskSpaceException;
import net.minecraft.ChatFormatting;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.FileUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.common.ForgeI18n;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupThread extends Thread {

    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD).appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2).appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2).appendLiteral('_')
            .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral('-')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2).appendLiteral('-')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter();
    public static final Logger LOGGER = LoggerFactory.getLogger(BackupThread.class);
    public static final long BACKUP_BUFFER_SIZE = 128L * 1024 * 1024; // 128 MB
    private final MinecraftServer server;
    private final boolean quiet;
    private final long lastSaved;
    private final boolean fullBackup;
    private final boolean scheduledBackup;
    private final String previousClaimScope;
    private final LevelStorageSource.LevelStorageAccess storageSource;
    private final Path backupPath;
    private final Set<Path> errors = new HashSet<>();

    private BackupThread(@Nonnull MinecraftServer server, boolean quiet, BackupData backupData) {
        this.server = server;
        this.storageSource = server.storageSource;
        this.quiet = quiet;
        this.scheduledBackup = backupData != null;
        if (backupData == null) {
            this.lastSaved = 0;
            this.fullBackup = true;
            this.previousClaimScope = "";
        } else {
            this.lastSaved = CommonConfig.backupType() == BackupType.MODIFIED_SINCE_LAST ? backupData.getLastSaved() : backupData.getLastFullBackup();
            this.fullBackup = CommonConfig.backupType() == BackupType.FULL_BACKUPS || (CommonConfig.useTickCounter() ? server.overworld().getGameTime() : System.currentTimeMillis()) - CommonConfig.getFullBackupTimer() > backupData.getLastFullBackup();
            this.previousClaimScope = backupData.getLastClaimScope();
        }
        this.setName("SimpleBackups");
        this.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        this.backupPath = CommonConfig.getOutputPath(this.storageSource.levelId);
    }

    public static boolean tryCreateBackup(MinecraftServer server) {
        BackupData backupData = BackupData.get(server);
        if (BackupThread.shouldRunBackup(server)) {
            BackupThread thread = new BackupThread(server, false, backupData);
            thread.start();
            long currentTime = CommonConfig.useTickCounter() ? server.overworld().getGameTime() : System.currentTimeMillis();
            backupData.updateSaveTime(currentTime);
            if (thread.fullBackup) {
                backupData.updateFullBackupTime(currentTime);
            }

            return true;
        }

        return false;
    }

    public static boolean shouldRunBackup(MinecraftServer server) {
        BackupData backupData = BackupData.get(server);
        if (!CommonConfig.isEnabled() || CommonConfig.backupsDisabledByJvmArg() || backupData.isPaused()) {
            return false;
        }

        if (CherishedWorldsCompat.isLoaded() && !CherishedWorldsCompat.isFavorite(server.storageSource.getLevelId())) {
            return false;
        }

        if (CommonConfig.useTickCounter()) {
            long gameTime = server.overworld().getGameTime();
            long lastSaved = backupData.getLastSaved();
            // convert timer from minutes into ticks
            long timer = CommonConfig.getTimer() * 20 * 60;
            return gameTime - lastSaved >= timer;
        }

        return System.currentTimeMillis() - CommonConfig.getTimer() > backupData.getLastSaved();
    }

    public static void createBackup(MinecraftServer server, boolean quiet) {
        BackupThread thread = new BackupThread(server, quiet, null);
        thread.start();
    }

    public void deleteFiles() {
        File backups = this.backupPath.toFile();
        if (backups.isDirectory()) {
            List<File> files = new ArrayList<>(Arrays.stream(Objects.requireNonNull(backups.listFiles())).filter(File::isFile).toList());
            if (files.size() > CommonConfig.getBackupsToKeep()) {
                files.sort(Comparator.comparingLong(File::lastModified));
                while (files.size() > CommonConfig.getBackupsToKeep()) {
                    boolean deleted = files.get(0).delete();
                    String name = files.get(0).getName();
                    if (deleted) {
                        files.remove(0);
                        LOGGER.info("Successfully deleted \"{}\"", name);
                    } else {
                        LOGGER.error("Could not delete old backup \"{}\"", name);
                        break;
                    }
                }
            }
        }
    }

    public void saveStorageSize() {
        try {
            while (this.getOutputFolderSize() > CommonConfig.getMaxDiskSize()) {
                File[] files = this.backupPath.toFile().listFiles();
                if (Objects.requireNonNull(files).length == 1) {
                    LOGGER.error("Cannot delete old files to save disk space. Only one backup file left!");
                    return;
                }

                Arrays.sort(Objects.requireNonNull(files), Comparator.comparingLong(File::lastModified));
                File file = files[0];
                String name = file.getName();
                if (file.delete()) {
                    LOGGER.info("Successfully deleted \"{}\"", name);
                }
            }
        } catch (NullPointerException | IOException e) {
            LOGGER.error("Cannot delete old files to save disk space", e);
        }
    }

    @Override
    public void run() {
        try {
            Path backupFilePath = this.getBackupFilePath();

            try {
                Files.createDirectories(this.backupPath);
                long start = System.currentTimeMillis();
                this.broadcast("simplebackups.backup_started", Style.EMPTY.withColor(ChatFormatting.GOLD));
                BackupResult backupResult = this.makeWorldBackup(backupFilePath);
                long end = System.currentTimeMillis();
                String time = Timer.getTimer(end - start);
                this.deleteFiles();
                this.saveStorageSize();

                boolean hasErrors = !this.errors.isEmpty();
                if (!hasErrors && this.scheduledBackup) {
                    this.server.executeBlocking(() -> BackupData.get(this.server).updateLastClaimScope(backupResult.claimScopeFingerprint()));
                }

                this.broadcast("simplebackups.backup_finished", Style.EMPTY.withColor(hasErrors ? ChatFormatting.YELLOW : ChatFormatting.GOLD),
                        time, StorageSize.getFormattedSize(backupResult.fileSize), StorageSize.getFormattedSize(this.getOutputFolderSize()));

                if (hasErrors) {
                    MutableComponent erroredFiles = Component.literal(this.errors.stream()
                            .map(file -> "- " + file.toString())
                            .collect(Collectors.joining("\n"))
                    );

                    this.broadcast("simplebackups.backup_errors", Style.EMPTY.withColor(ChatFormatting.RED)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, erroredFiles)), this.errors.size());
                    BackupThread.LOGGER.error("Skipped {} files during backup because of errors:", this.errors.size());
                    for (Path failedFile : this.errors) {
                        BackupThread.LOGGER.error(" - {}", failedFile);
                    }
                }
            } catch (NotEnoughDiskSpaceException e) {
                BackupThread.this.broadcast("simplebackups.not_enough_space", Style.EMPTY.withColor(ChatFormatting.RED));
                Files.deleteIfExists(backupFilePath);
            } catch (IOException e) {
                if (CommonConfig.deleteUnfinishedBackup()) {
                    this.broadcast("simplebackups.backup_failed_delete", Style.EMPTY.withColor(ChatFormatting.RED));
                    Files.deleteIfExists(backupFilePath);
                } else {
                    this.broadcast("simplebackups.backup_failed_continue", Style.EMPTY.withColor(ChatFormatting.RED));
                }

                SimpleBackups.LOGGER.error("Error backing up", e);
            }
        } catch (IOException e) {
            SimpleBackups.LOGGER.error("Error backing up", e);
        }
    }

    private Path getBackupFilePath() throws IOException {
        String fileName = this.storageSource.getLevelId() + "_" + LocalDateTime.now().format(FORMATTER);
        Path path = CommonConfig.getOutputPath(this.storageSource.getLevelId());

        Files.createDirectories(Files.exists(path) ? path.toRealPath() : path);

        return path.resolve(FileUtil.findAvailableName(path, fileName, ".zip"));
    }

    private long getOutputFolderSize() throws IOException {
        File[] files = this.backupPath.toFile().listFiles();
        long size = 0;
        try {
            for (File file : Objects.requireNonNull(files)) {
                size += Files.size(file.toPath());
            }
        } catch (NullPointerException e) {
            return 0;
        }

        return size;
    }

    private void broadcast(String message, Style style, Object... parameters) {
        //noinspection StringConcatenationArgumentToLogCall
        SimpleBackups.LOGGER.info(String.format(ForgeI18n.getPattern(message), parameters));
        if (CommonConfig.sendMessages() && !this.quiet) {
            this.server.execute(() -> {
                this.server.getPlayerList().getPlayers().forEach(player -> {
                    if (ServerConfig.messagesForEveryone() || player.hasPermissions(2)) {
                        player.sendSystemMessage(BackupThread.component(player, message, parameters).withStyle(style));
                    }
                });
            });

            if (Mc2DiscordCompat.isLoaded() && CommonConfig.mc2discord()) {
                Mc2DiscordCompat.announce(BackupThread.component(null, message, parameters));
            }
        }
    }

    public static MutableComponent component(@Nullable ServerPlayer player, String key, Object... parameters) {
        if (player != null) {
            ConnectionData data = NetworkHooks.getConnectionData(player.connection.connection);
            if (data != null && data.getModList().contains(SimpleBackups.MODID)) {
                return Component.translatable(key, parameters);
            }
        }

        return Component.literal(String.format(ForgeI18n.getPattern(key), parameters));
    }

    // vanilla copy with modifications
    private BackupResult makeWorldBackup(Path outputFile) throws IOException {
        this.storageSource.checkLock();
        AtomicReference<ClaimedMcaSelector> snapshot = new AtomicReference<>();
        AtomicReference<IOException> snapshotError = new AtomicReference<>();
        this.server.executeBlocking(() -> {
            if (CommonConfig.saveAll()) {
                this.server.saveEverything(true, false, true);
            }
            try {
                snapshot.set(FtbClaimSnapshot.capture(this.server));
            } catch (IOException e) {
                snapshotError.set(e);
            }
        });
        if (snapshotError.get() != null) {
            throw snapshotError.get();
        }

        Path levelPath = this.storageSource.getWorldDir().resolve(this.storageSource.levelId).toRealPath();
        ClaimedMcaSelector selector = snapshot.get();
        boolean claimsChanged = !this.fullBackup && !selector.scopeFingerprint().equals(this.previousClaimScope);

        try (ZipOutputStream zipStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputFile)))) {
            zipStream.setLevel(CommonConfig.getCompressionLevel());
            Path levelName = Paths.get(this.storageSource.levelId);

            List<Path> ignoredPaths = CommonConfig.getIgnoredPaths();
            List<Path> ignoredFiles = CommonConfig.getIgnoredFiles();
            String ignoredFilesRegex = CommonConfig.getIgnoredFilesRegex();
            boolean ignoreSomething = !ignoredPaths.isEmpty() || !ignoredFiles.isEmpty() || !ignoredFilesRegex.isEmpty();
            FileStore fileStore = Files.getFileStore(outputFile);

            Files.walkFileTree(levelPath, new SimpleFileVisitor<>() {

                @Nonnull
                public FileVisitResult visitFile(@Nonnull Path file, @Nonnull BasicFileAttributes attrs) throws IOException {
                    Path relativePath = levelPath.relativize(file);
                    if (!selector.includes(relativePath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (file.endsWith("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (file.endsWith("biomancy.spatial.db")) {
                        SimpleBackups.LOGGER.info("Skipping \"{}\" - see https://github.com/Elenterius/Biomancy/issues/175", levelPath.relativize(file));
                        return FileVisitResult.CONTINUE;
                    }

                    if (ignoreSomething && this.shouldSkipFile(levelPath.relativize(file))) {
                        SimpleBackups.LOGGER.debug("Skipping file: {}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    long lastModified = file.toFile().lastModified();
                    if (BackupThread.this.fullBackup || claimsChanged || selector.isClaimFile(relativePath)
                            || CommonConfig.useTickCounter() || lastModified - BackupThread.this.lastSaved > 0) {
                        if (fileStore.getUsableSpace() - attrs.size() - BACKUP_BUFFER_SIZE < 0L) {
                            throw new NotEnoughDiskSpaceException("Not enough space on disk to create backup");
                        }

                        String completePath = levelName.resolve(levelPath.relativize(file)).toString().replace('\\', '/');
                        ZipEntry zipentry = new ZipEntry(completePath);
                        try (InputStream inputStream = Files.newInputStream(file)) {
                            zipStream.putNextEntry(zipentry);
                            inputStream.transferTo(zipStream);
                            zipStream.closeEntry();
                        } catch (IOException e) {
                            this.visitFileFailed(file, e);
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Nonnull
                @Override
                public FileVisitResult visitFileFailed(@Nonnull Path file, @Nonnull IOException exc) throws IOException {
                    if (exc instanceof NoSuchFileException || exc instanceof FileNotFoundException) {
                        SimpleBackups.LOGGER.debug("Skipped vanished file: {}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    if (CommonConfig.collectErrors()) {
                        SimpleBackups.LOGGER.error("Failed to backup file: {}", file, exc);
                        BackupThread.this.errors.add(levelPath.relativize(file));
                        return FileVisitResult.CONTINUE;
                    }

                    IOException detailedException = new IOException("Failed to backup file: " + file, exc);
                    return super.visitFileFailed(file, detailedException);
                }

                private boolean shouldSkipFile(Path relativePath) {
                    return ignoredPaths.contains(relativePath.getParent())
                            || ignoredFiles.contains(relativePath)
                            || (!ignoredFilesRegex.isEmpty() && this.getNormalizedPath(relativePath).matches(ignoredFilesRegex));
                }

                private String getNormalizedPath(Path path) {
                    return path.toString().replace('\\', '/');
                }
            });
        }

        return new BackupResult(outputFile, Files.size(outputFile), selector.scopeFingerprint());
    }

    public record BackupResult(Path outputFile, long fileSize, String claimScopeFingerprint) {}

    private static class Timer {

        private static final SimpleDateFormat SECONDS = new SimpleDateFormat("s.SSS");
        private static final SimpleDateFormat MINUTES = new SimpleDateFormat("mm:ss");
        private static final SimpleDateFormat HOURS = new SimpleDateFormat("HH:mm");

        public static String getTimer(long milliseconds) {
            Date date = new Date(milliseconds);
            double seconds = milliseconds / 1000d;
            if (seconds < 60) {
                return SECONDS.format(date) + "s";
            } else if (seconds < 3600) {
                return MINUTES.format(date) + "min";
            } else {
                return HOURS.format(date) + "h";
            }
        }
    }
}
