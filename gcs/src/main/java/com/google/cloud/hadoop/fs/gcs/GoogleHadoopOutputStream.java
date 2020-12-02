/*
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import com.google.cloud.hadoop.gcsio.CreateFileOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageOptions;
import com.google.common.flogger.GoogleLogger;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileSystem;

/** A buffered output stream that allows writing to a GCS object. */
class GoogleHadoopOutputStream extends OutputStream {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  // All store IO access goes through this.
  private WritableByteChannel channel;

  // Output stream corresponding to channel.
  private OutputStream out;

  // Path of the file to write to.
  private URI gcsPath;

  // Statistics tracker provided by the parent GoogleHadoopFileSystemBase for recording
  // numbers of bytes written.
  private final FileSystem.Statistics statistics;

  /**
   * Constructs an instance of GoogleHadoopOutputStream object.
   *
   * @param ghfs Instance of GoogleHadoopFileSystemBase.
   * @param gcsPath Path of the file to write to.
   * @param statistics File system statistics object.
   * @throws IOException if an IO error occurs.
   */
  GoogleHadoopOutputStream(
      GoogleHadoopFileSystemBase ghfs,
      URI gcsPath,
      FileSystem.Statistics statistics,
      CreateFileOptions createFileOptions)
      throws IOException {
    logger.atFine().log(
        "GoogleHadoopOutputStream(gcsPath:%s, createFileOptions: %s)", gcsPath, createFileOptions);
    this.gcsPath = gcsPath;
    this.statistics = statistics;
    this.channel = createChannel(ghfs, gcsPath, createFileOptions);
    this.out =
        createOutputStream(this.channel, ghfs.getGcsFs().getOptions().getCloudStorageOptions());
  }

  private static WritableByteChannel createChannel(
      GoogleHadoopFileSystemBase ghfs, URI gcsPath, CreateFileOptions options) throws IOException {
    try {
      return ghfs.getGcsFs().create(gcsPath, options);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      // Need to convert to the Hadoop flavor of FileAlreadyExistsException.
      throw (FileAlreadyExistsException)
          new FileAlreadyExistsException("'" + gcsPath + "' already exists").initCause(e);
    }
  }

  private static OutputStream createOutputStream(
      WritableByteChannel channel, GoogleCloudStorageOptions gcsOptions) {
    OutputStream out = Channels.newOutputStream(channel);
    int bufferSize = gcsOptions.getWriteChannelOptions().getBufferSize();
    return bufferSize > 0 ? new BufferedOutputStream(out, bufferSize) : out;
  }

  /** Writes the specified byte to this output stream. */
  @Override
  public void write(int b) throws IOException {
    throwIfNotOpen();
    out.write(b);
    statistics.incrementBytesWritten(1);
    statistics.incrementWriteOps(1);
  }

  /**
   * Writes to this output stream 'len' bytes of the specified buffer starting at the given offset.
   */
  @Override
  public void write(byte[] b, int offset, int len) throws IOException {
    throwIfNotOpen();
    out.write(b, offset, len);
    statistics.incrementBytesWritten(len);
    statistics.incrementWriteOps(1);
  }

  /** Closes this output stream and releases any system resources associated with this stream. */
  @Override
  public void close() throws IOException {
    if (out != null) {
      try {
        long startTime = System.nanoTime();
        out.close();
        logger.atFine().log("close(%s)", gcsPath);
      } finally {
        out = null;
        channel = null;
      }
    }
  }

  private boolean isOpen() {
    return out != null;
  }

  private void throwIfNotOpen() throws IOException {
    if (!isOpen()) {
      throw new ClosedChannelException();
    }
  }

  WritableByteChannel getInternalChannel() {
    return channel;
  }
}
