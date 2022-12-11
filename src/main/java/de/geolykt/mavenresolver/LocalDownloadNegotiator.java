package de.geolykt.mavenresolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import de.geolykt.mavenresolver.misc.ConfusedResolverException;
import de.geolykt.mavenresolver.misc.InternalLastUpdatedFile;
import de.geolykt.mavenresolver.misc.MultiCompleteableFuture;

public class LocalDownloadNegotiator implements DownloadNegotiator {
    private final Path mavenLocal;
    private final Set<String> remoteIds = new HashSet<>();
    private final List<MavenRepository> remoteRepositories = new ArrayList<>();

    public LocalDownloadNegotiator(Path mavenLocal) {
        if (mavenLocal == null) {
            throw new IllegalArgumentException("The cache directory defined by \"mavenLocal\" may not be null!");
        }
        this.mavenLocal = mavenLocal;
        if (!Files.isDirectory(mavenLocal)) {
            if (Files.notExists(mavenLocal)) {
                try {
                    Files.createDirectories(mavenLocal);
                } catch (IOException e) {
                    throw new IllegalStateException("The \"mavenLocal\" argument must point to a directory. However this constructor was unable to make \"" + mavenLocal.toAbsolutePath() + "\" a directory.", e);
                }
            } else {
                throw new IllegalArgumentException("The \"mavenLocal\" argument must point to a directory. It currently points to " + mavenLocal.toAbsolutePath());
            }
        }
    }

    public Path getLocalCache() {
        return this.mavenLocal;
    }

    public LocalDownloadNegotiator addRepository(MavenRepository remote) {
        if (this.remoteIds.add(remote.getRepositoryId())) {
            this.remoteRepositories.add(remote);
        } else {
            throw new IllegalStateException("There is already a repository with the id \"" + remote.getRepositoryId() + "\" registered!");
        }
        return this;
    }

    public CompletableFuture<MavenResource> resolve(String path, Executor executor) {
        // TODO checksums, somehow
        Path localFile = this.mavenLocal.resolve(path);
        Path lastUpdateFile = this.mavenLocal.resolve(path + ".lastUpdated");
        InternalLastUpdatedFile lastUpdates;

        if (Files.exists(lastUpdateFile)) {
            lastUpdates = InternalLastUpdatedFile.parse(lastUpdateFile);
        } else {
            lastUpdates = new InternalLastUpdatedFile();
        }

        FileChannel resolverLockChannel;
        FileLock resolverLock;
        try {
            Path parentDir = localFile.toAbsolutePath().getParent();
            if (parentDir == null) {
                parentDir = Path.of("/");
            }
            Files.createDirectories(parentDir);
            Path resolverLockPath = parentDir.resolve("resolver-lock.lock");
            resolverLockChannel = FileChannel.open(resolverLockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.DELETE_ON_CLOSE);
            long idleTime = 0L;
            FileLock tempLock;
            while ((tempLock = resolverLockChannel.tryLock()) == null) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                }
                if ((idleTime += 10L) > 10_000L) {
                    throw new IOException("Waited more than 10 seconds to acquire lock on " + resolverLockPath.toAbsolutePath());
                }
            }
            resolverLock = tempLock;
        } catch (IOException e1) {
            return CompletableFuture.failedFuture(new IOException("Unable to acquire filesystem lock", e1).fillInStackTrace());
        }

        // FIXME we need to somehow remember which repositories stored what so even if it does not need to be updated
        // it is still traceable where the origin on the resource lies at.
        List<CompletableFuture<MavenResource>> updatingRepositories = new ArrayList<>();
        for (MavenRepository remote : this.remoteRepositories) {
            Long lastUpdate = lastUpdates.getLastFetchTime(remote.getPlaintextURL());
            if (lastUpdate == null || (System.currentTimeMillis() - lastUpdate) > remote.getUpdateIntervall()) {
                // TODO we can actually cheat here for non-xml files and return the future now
                // in a completed way and update the resource later on.
                // That being said this takes a bit more effort so we don't actually do it
                // TODO return early for sync futures
                updatingRepositories.add(remote.getResource(path, executor));
                lastUpdates.updateEntry(remote.getPlaintextURL(), "", System.currentTimeMillis());
            }
        }

        lastUpdates.write(lastUpdateFile);

        if (updatingRepositories.isEmpty()) {
            try {
                resolverLock.release();
                resolverLock.channel().close(); // Let's not have memory leaks
            } catch (Exception ignored) {
            }
            if (Files.exists(localFile)) {
                return CompletableFuture.completedFuture(getFlatfileResource(localFile));
            } else {
                return CompletableFuture.failedFuture(new ConfusedResolverException("Resolver has no repository it can fetch resources from").fillInStackTrace());
            }
        }
        CompletableFuture<MavenResource> combined = new MultiCompleteableFuture<>(updatingRepositories);
        CompletableFuture<MavenResource> reformed = combined.thenApply((in) -> {
            try {
                Files.copy(in.getPath(), localFile);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to copy file", e);
            }
            try {
                resolverLock.release();
                resolverLock.channel().close(); // Let's not have memory leaks
            } catch (Exception ignored) {
            }
            return getDownloadedResource(in.getSourceRepository(), localFile);
        }).exceptionally(t -> {
            try {
                resolverLock.release();
                resolverLock.channel().close();
            } catch (Exception ignored) {
            }
            if (Files.exists(localFile)) {
                return getFlatfileResource(localFile);
            }
            return null;
        });
        return reformed;
    }

    private static MavenResource getFlatfileResource(Path path) {
        return new MavenResource() {
            @Override
            public MavenRepository getSourceRepository() {
                // TODO Auto-generated method stub
                return null;
            }
            @Override
            public Path getPath() {
                return path;
            }
        };
    }

    private static MavenResource getDownloadedResource(MavenRepository source, Path path) {
        return new MavenResource() {
            @Override
            public MavenRepository getSourceRepository() {
                return source;
            }

            @Override
            public Path getPath() {
                return path;
            }
        };
    }
}
