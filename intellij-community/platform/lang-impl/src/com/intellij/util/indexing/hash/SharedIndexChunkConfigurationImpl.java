// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing.hash;

import com.google.common.base.Charsets;
import com.intellij.execution.process.ProcessIOExecutorService;
import com.intellij.index.SharedIndexExtensions;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Processor;
import com.intellij.util.hash.ContentHashEnumerator;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileBasedIndexImpl;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.provided.SharedIndexChunkLocator;
import com.intellij.util.indexing.provided.SharedIndexExtension;
import com.intellij.util.indexing.zipFs.UncompressedZipFileSystem;
import com.intellij.util.indexing.zipFs.UncompressedZipFileSystemProvider;
import com.intellij.util.io.DataEnumeratorEx;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.PersistentEnumeratorDelegate;
import com.intellij.util.io.zip.JBZipEntry;
import com.intellij.util.io.zip.JBZipFile;
import gnu.trove.TIntLongHashMap;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectIterator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class SharedIndexChunkConfigurationImpl implements SharedIndexChunkConfiguration {
  private static final Logger LOG = Logger.getInstance(SharedIndexChunkConfigurationImpl.class);

  private final DataEnumeratorEx<String> myChunkDescriptorEnumerator;

  private final TIntObjectHashMap<ContentHashEnumerator> myChunkEnumerators = new TIntObjectHashMap<>();
  private final TIntLongHashMap myChunkTimestamps = new TIntLongHashMap();

  private final ConcurrentMap<ID<?, ?>, ConcurrentMap<Integer, SharedIndexChunk>> myChunkMap = new ConcurrentHashMap<>();

  private final UncompressedZipFileSystem myReadSystem;
  private final ReadWriteLock myZipUpdateLock = new ReentrantReadWriteLock();

  @Override
  public void closeEnumerator(ContentHashEnumerator enumerator, int chunkId) {

  }

  @Override
  public <Value, Key> void processChunks(@NotNull ID<Key, Value> indexId, @NotNull Processor<HashBasedMapReduceIndex<Key, Value>> processor) {
    ConcurrentMap<Integer, SharedIndexChunk> map = myChunkMap.get(indexId);
    if (map == null) return;
    map.forEach((__, chunk) -> processor.process(chunk.getIndex()));
  }

  @Override
  @Nullable
  public <Value, Key> HashBasedMapReduceIndex<Key, Value> getChunk(@NotNull ID<Key, Value> indexId, int chunkId) {
    ConcurrentMap<Integer, SharedIndexChunk> map = myChunkMap.get(indexId);
    return map == null ? null : map.get(chunkId).getIndex();
  }

  public SharedIndexChunkConfigurationImpl() throws IOException {
    myChunkDescriptorEnumerator = new PersistentEnumeratorDelegate<>(getSharedIndexConfigurationRoot().resolve("descriptors"),
                                                                     EnumeratorStringDescriptor.INSTANCE, 32);

    //myWriteSystem = FileSystems.newFileSystem(URI.create("jar:" + getSharedIndexStorage().toUri().toString()), Collections.singletonMap("create", "true"));
    myReadSystem = new UncompressedZipFileSystem(getSharedIndexStorage(), new UncompressedZipFileSystemProvider());
    Disposer.register(ApplicationManager.getApplication(), () -> {
      try {
        myReadSystem.close();
      }
      catch (IOException e) {
        LOG.error(e);
      }
    });

    ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
      @Override
      public void projectClosed(@NotNull Project project) {
        List<SharedIndexChunk> toRemove =
          myChunkMap
            .values()
            .stream()
            .flatMap(chunks -> chunks.values().stream()).filter(chunk -> chunk.removeProject(project))
            .collect(Collectors.toList());

        for (SharedIndexChunk chunk : toRemove) {
          myChunkMap.get(chunk.getIndexName()).remove(chunk.getChunkId(), chunk);
        }
      }
    });
  }

  private JBZipFile getWriteFileSystem() throws IOException {
    ensureSharedIndexConfigurationRootExist();
    return new JBZipFile(getSharedIndexStorage().toFile());
  }

  private void ensureSharedIndexConfigurationRootExist() throws IOException {
    if (!Files.exists(getSharedIndexConfigurationRoot())) {
      Files.createDirectories(getSharedIndexConfigurationRoot());
    }
  }

  @NotNull
  private static Path getSharedIndexStorage() {
    return getSharedIndexConfigurationRoot().resolve("chunks.zip");
  }

  @Override
  public synchronized long tryEnumerateContentHash(byte[] hash) throws IOException {
    TIntObjectIterator<ContentHashEnumerator> iterator = myChunkEnumerators.iterator();
    while (iterator.hasNext()) {
      iterator.advance();

      ContentHashEnumerator enumerator = iterator.value();
      int id = Math.abs(enumerator.tryEnumerate(hash));
      if (id != 0) {
        return FileContentHashIndexExtension.getHashId(id, iterator.key());
      }

    }
    return FileContentHashIndexExtension.NULL_HASH_ID;
  }

  private void attachChunk(@NotNull SharedIndexChunkLocator.ChunkDescriptor chunkDescriptor,
                           @NotNull Project project) throws IOException {
    myZipUpdateLock.readLock().lock();
    try {
      for (SharedIndexChunk chunk : registerChunk(chunkDescriptor)) {
        ConcurrentMap<Integer, SharedIndexChunk> chunks = myChunkMap.computeIfAbsent(chunk.getIndexName(), __ -> new ConcurrentHashMap<>());
        chunk.addProject(project);
        chunks.put(chunk.getChunkId(), chunk);
      }

    } finally {
      myZipUpdateLock.readLock().unlock();
    }
  }

  public void loadSharedIndex(@NotNull Project project,
                              @NotNull InputStream stream,
                              @NotNull SharedIndexChunkLocator.ChunkDescriptor descriptor) {
    if (project.isDisposed()) return;

    Path targetFile;
    try {
      ensureSharedIndexConfigurationRootExist();
      targetFile = getSharedIndexConfigurationRoot().resolve(descriptor.getHash());
      Files.copy(stream, targetFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.error(e);
      return;
    }

    myZipUpdateLock.writeLock().lock();
    try {
      try (JBZipFile file = getWriteFileSystem(); ZipFile zipFile = new ZipFile(targetFile.toFile())) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();

          byte @NotNull [] content = FileUtil.loadBytes(zipFile.getInputStream(entry));
          String name = entry.getName();
          JBZipEntry createdEntry = file.getOrCreateEntry(name);
          createdEntry.setMethod(ZipEntry.STORED);
          createdEntry.setData(content);
        }
      }
      catch (IOException e) {
        LOG.error(e);
      }

      try {
        myReadSystem.sync();
      }
      catch (IOException e) {
        LOG.error(e);
      }
    } finally {
      myZipUpdateLock.writeLock().unlock();
    }

    try {
      attachChunk(descriptor, project);
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @Override
  public void locateIndexes(@NotNull Project project,
                            @NotNull Set<OrderEntry> entry,
                            @NotNull ProgressIndicator indicator) {
    Runnable runnable = () -> {
      for (SharedIndexChunkLocator locator : SharedIndexChunkLocator.EP_NAME.getExtensionList()) {
        locator.locateIndex(project, entry, indicator, new SharedIndexChunkLocator.SharedIndexHandler() {
          @Override
          public boolean onIndexAvailable(SharedIndexChunkLocator.@NotNull ChunkDescriptor descriptor) {
            return true;
          }

          @Override
          public void onIndexReceived(SharedIndexChunkLocator.@NotNull ChunkDescriptor descriptor, @NotNull InputStream inputStream) {
            loadSharedIndex(project, inputStream, descriptor);
          }
        });
      }
    };
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      runnable.run();
    } else {
      ProcessIOExecutorService.INSTANCE.execute(runnable);
    }
  }

  @NotNull
  private List<SharedIndexChunk> registerChunk(SharedIndexChunkLocator.@NotNull ChunkDescriptor descriptor) throws IOException {
    int chunkId = myChunkDescriptorEnumerator.enumerate(descriptor.getHash());
    if (chunkId == 0) {
      throw new RuntimeException("chunk " + descriptor.getHash() + " is not present");
    }
    ContentHashEnumerator enumerator;
    Path chunkRoot = myReadSystem.getPath(descriptor.getHash());
    synchronized (myChunkEnumerators) {
      enumerator = myChunkEnumerators.get(chunkId);
      if (enumerator == null) {
        //TODO: let's add "readOnly = true" parameter to ContentHashEnumerator to make it clear that we are not going to write to it.
        myChunkEnumerators.put(chunkId, enumerator = new ContentHashEnumerator(chunkRoot.resolve("hashes")));
      }
    }
    long timestamp;
    synchronized (myChunkTimestamps) {
      timestamp = myChunkTimestamps.get(chunkId);
      if (timestamp == 0) {
        timestamp = getTimestamp(chunkRoot);
        if (timestamp == -1) {
          throw new RuntimeException("corrupted shared index");
        }
        myChunkTimestamps.put(chunkId, timestamp);
      }
    }

    List<SharedIndexChunk> result = new ArrayList<>();
    for (Path child : Files.newDirectoryStream(chunkRoot)) {
      ID<?, ?> id = ID.findByName(child.getFileName().toString());
      if (id != null) {
        FileBasedIndexExtension<?, ?> extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.findFirstSafe(ex -> ex.getName().equals(id));
        if (extension != null) {
          SharedIndexExtension sharedExtension = SharedIndexExtensions.findExtension(extension);

          SharedIndexChunk chunk = new SharedIndexChunk(chunkRoot, id, chunkId, enumerator, timestamp);
          chunk.open(sharedExtension, extension, ((FileBasedIndexImpl)FileBasedIndex.getInstance()).getOrCreateFileContentHashIndex());
          result.add(chunk);
        }
      }
    }

    return result;
  }

  public static long getTimestamp(@NotNull Path chunkPath) {
    try {
      return StringUtil.parseLong(new String(Files.readAllBytes(chunkPath.resolve("timestamp")), Charsets.UTF_8), 0);
    }
    catch (IOException e) {
      LOG.info("timestamp is not found in " + chunkPath);
      return 0;
    }
  }

  public static void setTimestamp(@NotNull Path chunkPath, long timestamp) {
    byte[] bytes = String.valueOf(timestamp).getBytes(Charsets.UTF_8);
    try {
      Files.write(chunkPath.resolve("timestamp"), bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  private static Path getSharedIndexConfigurationRoot() {
    return PathManager.getIndexRoot().toPath().resolve("shared_indexes");
  }

}
