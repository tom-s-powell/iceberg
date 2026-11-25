/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.azure.adlsv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.azure.storage.file.datalake.models.PathItem;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.TestHelpers;
import org.apache.iceberg.io.BulkDeletionFailureException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.PrefixListingPage;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.util.SerializableFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class TestADLSFileIO {

  @Test
  public void testConstructorWithClientSupplier() {
    DataLakeFileSystemClient mockClient = mock(DataLakeFileSystemClient.class);
    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier = location -> mockClient;

    ADLSFileIO fileIO = new ADLSFileIO(supplier);

    // Verify properties are initialized (should not throw NPE)
    assertThat(fileIO.properties()).isNotNull();
    assertThat(fileIO.properties()).isEmpty();
  }

  @Test
  public void testConstructorWithClientSupplierAndInitialize() {
    DataLakeFileSystemClient mockClient = mock(DataLakeFileSystemClient.class);
    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier = location -> mockClient;

    ADLSFileIO fileIO = new ADLSFileIO(supplier);
    fileIO.initialize(ImmutableMap.of("key1", "value1"));

    // Verify properties from initialize are used
    assertThat(fileIO.properties()).containsEntry("key1", "value1");
  }

  @Test
  public void testClientSupplierIsUsed() {
    DataLakeFileSystemClient mockClient = mock(DataLakeFileSystemClient.class);

    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier = location -> mockClient;

    ADLSFileIO fileIO = new ADLSFileIO(supplier);
    fileIO.initialize(ImmutableMap.of());

    // Call client method to verify supplier is invoked
    DataLakeFileSystemClient client =
        fileIO.client("abfs://container@account.dfs.core.windows.net/path/to/file");

    assertThat(client).isEqualTo(mockClient);
  }

  @Test
  public void testClientSupplierWithoutInitialize() {
    DataLakeFileSystemClient mockClient = mock(DataLakeFileSystemClient.class);
    DataLakeFileClient mockFileClient = mock(DataLakeFileClient.class);

    when(mockClient.getFileClient("path/to/file")).thenReturn(mockFileClient);

    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier = location -> mockClient;

    ADLSFileIO fileIO = new ADLSFileIO(supplier);

    // Should work without calling initialize()
    // This verifies azureProperties is initialized in constructor
    assertThat(fileIO.properties()).isNotNull();
    assertThat(fileIO.properties()).isEmpty();

    // Should be able to create files without NPE
    InputFile inputFile =
        fileIO.newInputFile("abfs://container@account.dfs.core.windows.net/path/to/file");
    assertThat(inputFile).isNotNull();
  }

  @Test
  public void testNoArgConstructor() {
    ADLSFileIO fileIO = new ADLSFileIO();

    // Properties should be null before initialization
    // Initialize with empty map to avoid NPE
    fileIO.initialize(ImmutableMap.of());

    assertThat(fileIO.properties()).isNotNull();
    assertThat(fileIO.properties()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("org.apache.iceberg.TestHelpers#serializers")
  public void testSerializationWithClientSupplier(
      TestHelpers.RoundTripSerializer<FileIO> roundTripSerializer)
      throws IOException, ClassNotFoundException {
    // Use an AtomicInteger to track supplier invocations across serialization
    AtomicInteger supplierInvocationCount = new AtomicInteger(0);

    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier =
        location -> {
          supplierInvocationCount.incrementAndGet();
          // Return null - we're only testing serialization, not actual client usage
          return null;
        };

    ADLSFileIO fileIO = new ADLSFileIO(supplier);
    fileIO.initialize(ImmutableMap.of("key1", "value1", "key2", "value2"));

    // Verify original FileIO works
    assertThat(fileIO.properties()).containsEntry("key1", "value1");
    assertThat(fileIO.properties()).containsEntry("key2", "value2");

    // Serialize and deserialize
    FileIO deserializedFileIO = roundTripSerializer.apply(fileIO);

    // Verify properties are preserved after deserialization
    assertThat(deserializedFileIO.properties()).isEqualTo(fileIO.properties());

    // Verify the deserialized FileIO is an ADLSFileIO and can call client()
    assertThat(deserializedFileIO).isInstanceOf(ADLSFileIO.class);
    ADLSFileIO deserializedADLSFileIO = (ADLSFileIO) deserializedFileIO;

    // Call client() to verify the supplier was serialized and can be invoked
    DataLakeFileSystemClient client =
        deserializedADLSFileIO.client("abfs://container@account.dfs.core.windows.net/path");
    // The supplier returns null, so client should be null
    assertThat(client).isNull();
  }

  @ParameterizedTest
  @MethodSource("org.apache.iceberg.TestHelpers#serializers")
  public void testSerializationWithNoArgConstructor(
      TestHelpers.RoundTripSerializer<FileIO> roundTripSerializer)
      throws IOException, ClassNotFoundException {
    ADLSFileIO fileIO = new ADLSFileIO();
    fileIO.initialize(ImmutableMap.of("key1", "value1", "key2", "value2"));

    // Serialize and deserialize
    FileIO deserializedFileIO = roundTripSerializer.apply(fileIO);

    // Verify properties are preserved after deserialization
    assertThat(deserializedFileIO.properties()).isEqualTo(fileIO.properties());
  }

  @Test
  public void testClientSupplierIsCachedPerContainer() {
    DataLakeFileSystemClient mockClient1 = mock(DataLakeFileSystemClient.class);
    DataLakeFileSystemClient mockClient2 = mock(DataLakeFileSystemClient.class);
    AtomicInteger supplierInvocationCount = new AtomicInteger(0);

    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier =
        location -> {
          supplierInvocationCount.incrementAndGet();
          // Return different clients for different containers
          return location.container().orElse("").equals("container1") ? mockClient1 : mockClient2;
        };

    ADLSFileIO fileIO = new ADLSFileIO(supplier);

    // Same container - should cache
    DataLakeFileSystemClient client1 =
        fileIO.client("abfs://container1@account.dfs.core.windows.net/path1");
    DataLakeFileSystemClient client2 =
        fileIO.client("abfs://container1@account.dfs.core.windows.net/path2");

    assertThat(supplierInvocationCount.get()).isEqualTo(1);
    assertThat(client1).isSameAs(client2);

    // Different container - should call supplier again
    DataLakeFileSystemClient client3 =
        fileIO.client("abfs://container2@account.dfs.core.windows.net/path3");

    assertThat(supplierInvocationCount.get()).isEqualTo(2);
    assertThat(client3).isSameAs(mockClient2);
  }

  @Test
  public void testClientCachedPerStorageAccountAndContainer() {
    DataLakeFileSystemClient mockClient1 = mock(DataLakeFileSystemClient.class);
    DataLakeFileSystemClient mockClient2 = mock(DataLakeFileSystemClient.class);
    DataLakeFileSystemClient mockClient3 = mock(DataLakeFileSystemClient.class);
    AtomicInteger supplierInvocationCount = new AtomicInteger(0);

    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier =
        location -> {
          supplierInvocationCount.incrementAndGet();
          String host = location.host();
          String container = location.container().orElse("");
          if (host.equals("account1.dfs.core.windows.net") && container.equals("container")) {
            return mockClient1;
          } else if (host.equals("account2.dfs.core.windows.net")
              && container.equals("container")) {
            return mockClient2;
          } else {
            return mockClient3;
          }
        };

    ADLSFileIO fileIO = new ADLSFileIO(supplier);

    // Same account, same container - should cache
    DataLakeFileSystemClient client1 =
        fileIO.client("abfs://container@account1.dfs.core.windows.net/path1");
    DataLakeFileSystemClient client2 =
        fileIO.client("abfs://container@account1.dfs.core.windows.net/path2");

    assertThat(supplierInvocationCount.get()).isEqualTo(1);
    assertThat(client1).isSameAs(client2);
    assertThat(client1).isSameAs(mockClient1);

    // Different account, same container - should call supplier again
    DataLakeFileSystemClient client3 =
        fileIO.client("abfs://container@account2.dfs.core.windows.net/path3");

    assertThat(supplierInvocationCount.get()).isEqualTo(2);
    assertThat(client3).isSameAs(mockClient2);
    assertThat(client3).isNotSameAs(client1);

    // Same account as first, different container - should call supplier again
    DataLakeFileSystemClient client4 =
        fileIO.client("abfs://other@account1.dfs.core.windows.net/path4");

    assertThat(supplierInvocationCount.get()).isEqualTo(3);
    assertThat(client4).isSameAs(mockClient3);
  }

  @Test
  public void testClientSupplierCachingIsThreadSafe() throws Exception {
    DataLakeFileSystemClient mockClient = mock(DataLakeFileSystemClient.class);
    AtomicInteger supplierInvocationCount = new AtomicInteger(0);

    SerializableFunction<ADLSLocation, DataLakeFileSystemClient> supplier =
        location -> {
          supplierInvocationCount.incrementAndGet();
          return mockClient;
        };

    ADLSFileIO fileIO = new ADLSFileIO(supplier);

    // Run multiple threads concurrently calling client() with same container
    int numThreads = 10;
    Thread[] threads = new Thread[numThreads];
    DataLakeFileSystemClient[] results = new DataLakeFileSystemClient[numThreads];

    for (int i = 0; i < numThreads; i++) {
      final int index = i;
      threads[i] =
          new Thread(
              () -> {
                results[index] =
                    fileIO.client("abfs://container@account.dfs.core.windows.net/path/" + index);
              });
    }

    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }

    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }

    // Verify supplier was only called once even with concurrent access (same container)
    assertThat(supplierInvocationCount.get()).isEqualTo(1);

    // Verify all threads got the same client
    for (DataLakeFileSystemClient result : results) {
      assertThat(result).isSameAs(mockClient);
    }
  }

  @Test
  void deleteFilesReportsFailures() {
    DataLakeFileSystemClient mockClient = mock(DataLakeFileSystemClient.class);
    DataLakeFileClient failingFileClient =
        mock(
            DataLakeFileClient.class,
            invocation -> {
              throw new RuntimeException("Delete failed");
            });
    when(mockClient.getFileClient("path/to/failing")).thenReturn(failingFileClient);
    when(mockClient.getFileClient("path/to/deleted")).thenReturn(mock(DataLakeFileClient.class));

    ADLSFileIO fileIO = new ADLSFileIO(location -> mockClient);

    assertThatThrownBy(
            () ->
                fileIO.deleteFiles(
                    List.of(
                        "abfs://container@account.dfs.core.windows.net/path/to/failing",
                        "abfs://container@account.dfs.core.windows.net/path/to/deleted")))
        .isInstanceOf(BulkDeletionFailureException.class)
        .hasMessage("Failed to delete 1 files");
  }

  @Test
  void listDirectoryReadsPagesLazily() {
    String prefix = "abfs://container@account.dfs.core.windows.net/dir/";
    OffsetDateTime createdAt = OffsetDateTime.now();
    AtomicInteger pagesRead = new AtomicInteger();
    PagedIterable<PathItem> response =
        new PagedIterable<>(
            () -> {
              pagesRead.incrementAndGet();
              return page(List.of(pathItem("dir/file", false, createdAt)), "next");
            },
            token -> {
              pagesRead.incrementAndGet();
              assertThat(token).isEqualTo("next");
              return page(List.of(pathItem("dir/sub", true, createdAt)), null);
            });
    DataLakeFileSystemClient client = mock(DataLakeFileSystemClient.class);
    when(client.listPaths(any(), any())).thenReturn(response);
    ADLSFileIO fileIO = new ADLSFileIO(location -> client);

    Iterable<PrefixListingPage> listing = fileIO.listDirectory(prefix);
    assertThat(pagesRead).hasValue(0);

    Iterator<PrefixListingPage> pages = listing.iterator();
    PrefixListingPage firstPage = pages.next();
    assertThat(pagesRead).hasValue(1);
    assertThat(firstPage.files())
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.location()).isEqualTo(prefix + "file");
              assertThat(file.size()).isEqualTo(123L);
              assertThat(file.createdAtMillis()).isEqualTo(createdAt.toInstant().toEpochMilli());
            });
    assertThat(firstPage.subPrefixes()).isEmpty();

    PrefixListingPage secondPage = pages.next();
    assertThat(pagesRead).hasValue(2);
    assertThat(secondPage.files()).isEmpty();
    assertThat(secondPage.subPrefixes()).containsExactly(prefix + "sub/");
    assertThat(pages.hasNext()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abfs://container@account.dfs.core.windows.net/",
        "abfss://container@account.dfs.core.windows.net/",
        "abfs://account.dfs.core.windows.net/",
        "wasbs://container@account.blob.core.windows.net/"
      })
  void listDirectoryReturnsReusableLocations(String prefix) {
    OffsetDateTime createdAt = OffsetDateTime.now();
    DataLakeFileSystemClient client = mock(DataLakeFileSystemClient.class);
    when(client.listPaths(
            argThat(
                options ->
                    options != null && options.getPath().isEmpty() && !options.isRecursive()),
            isNull()))
        .thenReturn(
            new PagedIterable<>(
                () ->
                    page(
                        List.of(
                            pathItem("file", false, createdAt), pathItem("sub", true, createdAt)),
                        null)));
    when(client.listPaths(
            argThat(
                options ->
                    options != null && "sub/".equals(options.getPath()) && !options.isRecursive()),
            isNull()))
        .thenReturn(
            new PagedIterable<>(() -> page(List.of(pathItem("sub/file", false, createdAt)), null)));
    ADLSFileIO fileIO = new ADLSFileIO(location -> client);

    assertThat(fileIO.supportsDirectoryListing(prefix)).isTrue();
    PrefixListingPage listing = fileIO.listDirectory(prefix).iterator().next();
    assertThat(listing.files()).extracting(FileInfo::location).containsExactly(prefix + "file");
    assertThat(listing.subPrefixes()).containsExactly(prefix + "sub/");

    String subPrefix = listing.subPrefixes().iterator().next();
    assertThat(fileIO.supportsDirectoryListing(subPrefix)).isTrue();
    PrefixListingPage childListing = fileIO.listDirectory(subPrefix).iterator().next();
    assertThat(childListing.files())
        .extracting(FileInfo::location)
        .containsExactly(subPrefix + "file");
    assertThat(childListing.subPrefixes()).isEmpty();
  }

  @Test
  void listDirectoryReturnsEmptyForMissingDirectory() {
    DataLakeStorageException missingDirectory = mock(DataLakeStorageException.class);
    when(missingDirectory.getStatusCode()).thenReturn(404);
    DataLakeFileSystemClient client = mock(DataLakeFileSystemClient.class);
    when(client.listPaths(any(), any()))
        .thenReturn(
            new PagedIterable<>(
                () -> {
                  throw missingDirectory;
                }));
    ADLSFileIO fileIO = new ADLSFileIO(location -> client);

    assertThat(fileIO.listDirectory("abfs://container@account.dfs.core.windows.net/missing/"))
        .isEmpty();
  }

  @Test
  void listDirectoryPropagatesFailures() {
    DataLakeStorageException forbidden = mock(DataLakeStorageException.class);
    when(forbidden.getStatusCode()).thenReturn(403);
    DataLakeFileSystemClient client = mock(DataLakeFileSystemClient.class);
    when(client.listPaths(any(), any()))
        .thenReturn(
            new PagedIterable<>(
                () -> {
                  throw forbidden;
                }));
    ADLSFileIO fileIO = new ADLSFileIO(location -> client);

    assertThatThrownBy(
            () ->
                fileIO
                    .listDirectory("abfs://container@account.dfs.core.windows.net/dir/")
                    .iterator()
                    .hasNext())
        .isSameAs(forbidden);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "abfs://container@account.dfs.core.windows.net",
        "abfs://container@account.dfs.core.windows.net/dir"
      })
  void listDirectoryRejectsInvalidPrefix(String prefix) {
    DataLakeFileSystemClient client = mock(DataLakeFileSystemClient.class);
    ADLSFileIO fileIO = new ADLSFileIO(location -> client);

    assertThat(fileIO.supportsDirectoryListing(prefix)).isFalse();
    assertThatThrownBy(() -> fileIO.listDirectory(prefix))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix");
    verifyNoInteractions(client);
  }

  private static PagedResponse<PathItem> page(List<PathItem> paths, String continuationToken) {
    return new PagedResponseBase<>(null, 200, null, paths, continuationToken, null);
  }

  private static PathItem pathItem(String path, boolean directory, OffsetDateTime createdAt) {
    return new PathItem(
        "tag", createdAt, 123L, "group", directory, path, "owner", "permissions", createdAt, null);
  }
}
