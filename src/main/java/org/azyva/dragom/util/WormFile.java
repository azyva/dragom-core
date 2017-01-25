package org.azyva.dragom.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Helps manage inter-process and inter-thread (same process) file access
 * synchronization based on a write-once read-many access pattern.
 *
 * <p>Although it can be used in other scenarios, the use case this class was
 * designed to support is that of 2-level cache for data that is often required
 * and is expensive to obtain. That data is cached in a file managed by this class
 * as well as in memory to avoid having to load it from a file every time it it
 * needed.
 *
 * <p>Loading the data from the file is required when the file is changed outside
 * of the JVM process. This modification-timestamp-based checking functionality is
 * provided for that purpose.
 *
 * <p>When the required data is not available (in memory and in the file), it must
 * be obtained and updated in memory (not under the control of this class) and in
 * the file, taking into account that:
 * <ul>
 * <li>Multiple processes may want to update the file simultaneously
 * <li>A process may want to update the file while it is being read by another
 * </ul>
 * so that synchronization is required. Also in some cases, synchronization may be
 * required between threads of the same process. Inter-process and inter-thread
 * synchronization functionality is provided for that purpose.
 *
 * <p>The java.nio.channels.FileChannel and java.nio.channels.FileLock are used to
 * implement the required inter-process file locking. But such facilities are
 * global to the JVM process and are not designed to handle inter-thread file
 * access synchronization.
 *
 * <p>For inter-thread synchronization, java.util.concurrent.locks.ReadWriteLock is
 * used.
 *
 * <p>This class wraps all these features in an easy to use abstraction.
 * We are not talking about fully concurrent write access. The idea is that once
 * a process has written the file, those processes who have already read it are
 * expected to read it again to refresh their view of the file.
 *
 * @author David Raymond
 */
public class WormFile {
  /**
   * Maps canonicalized file paths to instances of this class.
   *
   * <p>One instance of this class exists for a given file. It is this instance which
   * contain the synchronization objects.
   */
  private static Map<Path, WormFile> mapWormFile = new HashMap<Path, WormFile>();

  /**
   * Path to the file.
   */
  private Path pathFile;

  /**
   * ReadWriteLock used to manaage inter-thread synchronization.
   */
  private ReadWriteLock readWriteLock;

  /**
   * FileLock used to manage inter-process synchronization.
   */
  private FileLock fileLock;

  /**
   * Current read access count. 0 if not currently accessed for reading.
   */
  private int readCount;

  /**
   * Indicates if write access is currently effective. false if not currently
   * accessed for writing.
   */
  private boolean indWrite;

  /**
   * WormFile by itself does not manage the last modification timestamp of the file
   * nor its comparison with the timestamp corresponding to the time the data was
   * loaded into memory. This is not possible since only one instance of WormFile
   * exists for each file, and multiple threads in the same process may need to
   * manage this refresh process.
   *
   * <p>Instead, for those callers who need to manage some refresh process, this
   * separate class is provided. Multiple instances of this class can be created
   * for the same WorkFile, allowing multiple callers in the same process to manage a
   * refresh process for the same file.
   */
  public static class WormFileCache {
    /**
     * WormFile.
     */
    WormFile wormFile;

    /**
     * Last modification timestamp of the file as of the last (released) access.
     */
    private long lastModifiedTimestamp;

    /**
     * Constructor.
     *
     * @param wormFile WormFile.
     */
    private WormFileCache(WormFile wormFile) {
      this.wormFile = wormFile;
    }

    /**
     * Passthrough for {@link WormFile#getInputStream}.
     *
     * @return See description.
     */
    public InputStream getInputStream() {
      return this.wormFile.getInputStream();
    }

    /**
     * Passthrough for {@link WormFile#getOutputStream}.
     *
     * @return See description.
     */
    public OutputStream getOutputStream() {
      return this.wormFile.getOutputStream();
    }

    /**
     * Passthrough for {@link WormFile#reserveAccess}.
     *
     * @param indWriteRequest See description.
     * @return See description.
     */
    public AccessHandle reserveAccess(boolean indWriteRequest) {
      AccessHandle accessHandle;

      accessHandle = this.wormFile.reserveAccess(indWriteRequest);
      accessHandle.setWormFileCache(this);
      return accessHandle;
    }

    /**
     * Passthrough for {@link WormFile#isExists}.
     *
     * @return See description.
     */
    public boolean isExists() {
      return this.wormFile.isExists();
    }

    /**
     * @return Indicates if the file has been modified since last (released) accessed
     *   based on its last modification stamp.
     */
    public boolean isModified() {
      return (this.lastModifiedTimestamp == 0) || (this.lastModifiedTimestamp != this.wormFile.pathFile.toFile().lastModified());
    }

    /**
     * Updates the last modification timestamp.
     *
     * <p>This private method is called by {@link AccessHandle#release} and therefore
     * when access is released.
     */
    private void updateLastModifiedTimestamp() {
      this.lastModifiedTimestamp = this.wormFile.pathFile.toFile().lastModified();
    }
  }

  /**
   * Represents an ongoing access to the file.
   *
   * <p>An AccessHandle is obtained either from {@link WormFileCache#reserveAccess} or
   * {@link WormFile#reserveAccess} and must be released when access is not required
   * anymore.
   *
   * <p>An AccessHandle is implicitly associated with the WormFile for which access
   * is reserved.
   *
   * <p>An AccessHandle is also explicitly associated with the WormFileCache if used
   * by the caller. This is required since when an access is released, the last
   * modification timestamp must be updated in the WormFileCache.
   */
  public class AccessHandle {
    /**
     * Indicates this AccessHandle is active.
     */
    private boolean indActive;

    /**
     * The WormFileCache
     */
    WormFileCache wormFileCache;

    /**
     * Constructor.
     */
    AccessHandle() {
      this.indActive = true;
    }

    /**
     * Called when the caller obtains an AccessHandle from a WormFileCache.
     *
     * @param wormFileCache WormFileCache.
     */
    private void setWormFileCache(WormFileCache wormFileCache) {
      this.wormFileCache = wormFileCache;
    }

    /**
     * Releases the access represented by this AccessHandle.
     */
    public void release() {
      if (!this.indActive) {
        throw new RuntimeException("Handle not active.");
      }

      WormFile.this.releaseAccess();
      this.indActive = false;

      this.wormFileCache.updateLastModifiedTimestamp();
    }
  }

  /**
   * Constructor.
   *
   * <p>Instances of this class can only be created by {@link #get}.
   *
   * @param pathFile Path to the file.
   */
  private WormFile(Path pathFile) {
    this.pathFile = pathFile;
    this.readWriteLock = new ReentrantReadWriteLock();
  }

  /**
   * Returns the singleton WormFile for the given file path.
   *
   * <p>The file path is canonicalized internally.
   *
   * @param pathFile Path to the file.
   * @return See description.
   */
  public static synchronized WormFile get(Path pathFile) {
    Path pathFileCanonicalized;
    WormFile wormFile;

    pathFileCanonicalized = pathFile.toAbsolutePath().normalize();

    wormFile = WormFile.mapWormFile.get(pathFileCanonicalized);

    if (wormFile == null) {
      wormFile = new WormFile(pathFileCanonicalized);
      WormFile.mapWormFile.put(pathFileCanonicalized, wormFile);
    }

    return wormFile;
  }

  /**
   * Returns a new WormFileCache for the singleton WormFile for the given file path
   * as returned by {@link #get}.
   *
   * @param pathFile Path to the file.
   * @return See description.
   */
  public static WormFileCache getCache(Path pathFile) {
    return new WormFileCache(WormFile.get(pathFile));
  }

  /**
   * Gets an InputStream allowing to read the file.
   *
   * <p>When a file is managed by this class, read access to it must be performed
   * only though this InputStream. The reason is because of the way FileChannel's
   * and FileLock's work in Java.
   *
   * <p>Access to the file must have been reserved before.
   *
   * <p>The InputStream must not be closed by the caller. It will get implicitly
   * closed when the underlying FileChannel gets closed by releasing access to the
   * file.
   *
   * @return See description.
   */
  //TODO: Not sure we can get 2 different InputStream's (within the same JVM process) for the same file.
  //Will the position interfere?
  public InputStream getInputStream() {
    if (!this.indWrite && (this.readCount == 0)) {
      throw new RuntimeException("Access to WormFile " + this.pathFile + " must be reserved.");
    }

    try {
      if (this.indWrite) {
        FileChannel fileChannel;

        fileChannel = this.fileLock.channel();
        fileChannel.position(0);

        return Channels.newInputStream(fileChannel);
      } else {
        // TODO: We think that by creating a new FileInputStream without going through
        // the FileChannel, they will be independent and will not interfere. But this has
        // not been tested.
        return new FileInputStream(this.pathFile.toFile());
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Gets an OutputStream allowing to read the file.
   *
   * <p>When a file is managed by this class, write access to it must be performed
   * only though this OutputStream. The reason is because of the way FileChannel's
   * and FileLock's work in Java.
   *
   * <p>Access to the file must have been reserved for writing before.
   *
   * <p>The OutputStream must not be closed by the caller. It will get implicitly
   * closed when the underlying FileChannel gets closed by releasing access to the
   * file.
   *
   * @return See description.
   */
  public OutputStream getOutputStream() {
    FileChannel fileChannel;

    if (!this.indWrite) {
      throw new RuntimeException("Access to WormFile " + this.pathFile + " must be reserved for writing.");
    }

    try {
      fileChannel = this.fileLock.channel();
      fileChannel.position(0);

      return Channels.newOutputStream(fileChannel);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Reserves access to the file.
   *
   * <p>While access is reserved, {@link #getInputStream} and
   * {@link #getOutputStream} can be used to get access to the file.
   *
   * <p>Access to the file must be released when not needed anymore by calling
   * {@link AccessHandle#release} from the same thread.
   *
   * @param indWriteRequest Indicates if write access is requested (which also
   *   allows reading) as opposed to only reading.
   * @return AccessHandle.
   */
  public AccessHandle reserveAccess(boolean indWriteRequest) {
    if (indWriteRequest) {
      this.readWriteLock.writeLock().lock();

      // This is a sanity check. Normally, the condition should not be true.
      if ((this.indWrite) || (this.readCount != 0) || (this.fileLock != null)) {
        throw new RuntimeException("Access to WormFile for " + this.pathFile + " should not be reserved.");
      }

      this.indWrite = true;

      try {
        // When requesting write access, we allow the file to not exist and we created it
        // (empty) if necessary.
        if (!this.pathFile.toFile().exists()) {
          this.pathFile.getParent().toFile().mkdirs();
        }

        // This creates the file if it does not exist.
        this.fileLock = FileChannel.open(this.pathFile, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE).lock();
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }

      // The AccessHandle is implicitly associated with this WormFile because of the
      // nested class semantics of Java.
      return new AccessHandle();
    } else {
      this.readWriteLock.readLock().lock();

      // This is a sanity check. Normally, the condition should not be true.
      if (this.indWrite) {
        throw new RuntimeException("Access to WormFile for " + this.pathFile + " should not be reserved for writing.");
      }

      // We must synchronize this block since multiple read access can be requested simultaneously.
      synchronized (this) {
        // We need to acquire the FileLock only for the first read access since FileLock's
        // are global to the JVM process.
        if (this.readCount++ == 0) {
          // This is a sanity check. Normally, the condition should not be true.
          if (this.fileLock != null) {
            throw new RuntimeException("Access to WormFile for " + this.pathFile + " should not be reserved.");
          }

          try {
            // This can throw FileNotFoundException, which is OK since for read access, the
            // file must already exist.
            this.fileLock = FileChannel.open(this.pathFile, StandardOpenOption.READ).lock(0, Long.MAX_VALUE, true);
          } catch (IOException ioe) {
            throw new RuntimeException(ioe);
          }
        } else {
          // This is a sanity check. Normally, the condition should not be true.
          if ((this.fileLock == null) || !this.fileLock.isValid() || !this.fileLock.isShared()) {
            throw new RuntimeException("Access to WormFile for " + this.pathFile + " should already be reserved for reading.");
          }
        }
      }

      return new AccessHandle();
    }
  }

  /**
   * Releases access to the file.
   *
   * <p>Called by {@link AccessHandle#release}.
   */
  private void releaseAccess() {
    // This is a sanity check. Normally, the condition should not be true.
    if ((this.fileLock == null) || !this.fileLock.isValid()) {
      throw new RuntimeException("Access to WormFile for " + this.pathFile + " not reserved for writing.");
    }

    if (this.indWrite) {
      // This is a sanity check. Normally, the condition should not be true.
      if (this.fileLock.isShared()) {
        throw new RuntimeException("Access to WormFile for " + this.pathFile + " not reserved for writing.");
      }

      try {
        this.fileLock.release();
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }

      this.fileLock = null;

      this.readWriteLock.writeLock().unlock();

      this.indWrite = false;
    } else {
      // This is a sanity check. Normally, the condition should not be true.
      if ((this.readCount == 0) || !this.fileLock.isShared()) {
        throw new RuntimeException("Access to WormFile for " + this.pathFile + " not reserved for reading.");
      }

      // We must synchronize this block since multiple read access can be released simultaneously.
      synchronized(this) {
        // We need to release the FileLock only for the last read access since FileLock's
        // are global to the JVM process.
        if (--this.readCount == 0) {
          try {
            this.fileLock.release();
          } catch (IOException ioe) {
            throw new RuntimeException(ioe);
          }

          this.fileLock = null;
        }
      }

      this.readWriteLock.readLock().unlock();
    }
  }

  /**
   * @return Indicates if the file exists.
   */
  public boolean isExists() {
    return this.pathFile.toFile().exists();
  }
}
