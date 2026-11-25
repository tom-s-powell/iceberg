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
package org.apache.iceberg.aws.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.PrefixListingPage;
import org.apache.iceberg.io.StorageCredential;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

class TestS3FileIODirectoryListing {
  private final S3Client client = mock(S3Client.class);

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"s3://bucket", "s3://bucket/path", "s3://bucket--x-s3/path"})
  void rejectsInvalidPrefixes(String prefix) {
    try (S3FileIO fileIO = new S3FileIO(() -> client)) {
      assertThat(fileIO.supportsDirectoryListing(prefix)).isFalse();
      assertThatThrownBy(() -> fileIO.listDirectory(prefix))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must end with '/'");
      verifyNoInteractions(client);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "s3://bucket/path?x=/",
        "s3://bucket/path#fragment/",
        "s3://bucket/path/?x=/",
        "s3://bucket/path/#fragment/"
      })
  void rejectsPrefixesWithQueryOrFragment(String prefix) {
    try (S3FileIO fileIO = new S3FileIO(() -> client)) {
      assertThat(fileIO.supportsDirectoryListing(prefix)).isFalse();
      assertThatThrownBy(() -> fileIO.listDirectory(prefix))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not contain a query or fragment");
      verifyNoInteractions(client);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"s3", "s3a", "s3n", "https"})
  void returnsFilesAndSubPrefixesByPage(String scheme) {
    Instant modified = Instant.parse("2026-01-01T00:00:00Z");
    ListObjectsV2Response first =
        ListObjectsV2Response.builder()
            .contents(
                S3Object.builder().key("path/").size(0L).lastModified(modified).build(),
                S3Object.builder().key("path/file").size(12L).lastModified(modified).build())
            .commonPrefixes(CommonPrefix.builder().prefix("path/a/").build())
            .nextContinuationToken("next")
            .isTruncated(true)
            .build();
    ListObjectsV2Response second =
        ListObjectsV2Response.builder()
            .commonPrefixes(CommonPrefix.builder().prefix("path/b/").build())
            .isTruncated(false)
            .build();
    mockListing("bucket", "path/", first, second);

    try (S3FileIO fileIO = new S3FileIO(() -> client)) {
      String prefix = scheme + "://bucket/path/";
      assertThat(fileIO.supportsDirectoryListing(prefix)).isTrue();
      List<PrefixListingPage> pages = Lists.newArrayList(fileIO.listDirectory(prefix));

      assertThat(pages).hasSize(2);
      assertThat(pages.get(0).files())
          .singleElement()
          .satisfies(
              file -> {
                assertThat(file.location()).isEqualTo(prefix + "file");
                assertThat(file.size()).isEqualTo(12L);
                assertThat(file.createdAtMillis()).isEqualTo(modified.toEpochMilli());
              });
      assertThat(pages.get(0).subPrefixes()).containsExactly(prefix + "a/");
      assertThat(pages.get(1).files()).isEmpty();
      assertThat(pages.get(1).subPrefixes()).containsExactly(prefix + "b/");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"bucket", "bucket--usw2-az1--x-s3"})
  void listsBucketRoot(String bucket) {
    mockListing(
        bucket,
        "",
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("file").size(0L).lastModified(Instant.EPOCH).build())
            .commonPrefixes(CommonPrefix.builder().prefix("child/").build())
            .build());

    try (S3FileIO fileIO = new S3FileIO(() -> client)) {
      String prefix = "s3://" + bucket + "/";
      assertThat(fileIO.supportsDirectoryListing(prefix)).isTrue();
      assertThat(fileIO.listDirectory(prefix))
          .singleElement()
          .satisfies(
              page -> {
                assertThat(page.files())
                    .extracting(FileInfo::location)
                    .containsExactly(prefix + "file");
                assertThat(page.subPrefixes()).containsExactly(prefix + "child/");
              });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void listsDirectoryBucketWithoutPrefixNormalization(boolean normalizePrefix) {
    String bucket = "bucket--usw2-az1--x-s3";
    mockListing(bucket, "path/", ListObjectsV2Response.builder().build());

    try (S3FileIO fileIO = new S3FileIO(() -> client)) {
      fileIO.initialize(
          Map.of(
              S3FileIOProperties.S3_DIRECTORY_BUCKET_LIST_PREFIX_AS_DIRECTORY,
              Boolean.toString(normalizePrefix)));
      String prefix = "s3://" + bucket + "/path/";
      assertThat(fileIO.supportsDirectoryListing(prefix)).isTrue();
      assertThat(fileIO.listDirectory(prefix))
          .singleElement()
          .satisfies(
              page -> {
                assertThat(page.files()).isEmpty();
                assertThat(page.subPrefixes()).isEmpty();
              });
    }
  }

  @Test
  void preservesLocationForAccessPointsAndStorageCredentials() {
    String prefix = "s3://bucket/path/";
    mockListing(
        "scoped-access-point",
        "path/",
        ListObjectsV2Response.builder()
            .commonPrefixes(CommonPrefix.builder().prefix("path/child/").build())
            .build());
    mockListing("scoped-access-point", "path/child/", ListObjectsV2Response.builder().build());

    try (S3FileIO fileIO = new S3FileIO(() -> client)) {
      fileIO.initialize(
          Map.of(S3FileIOProperties.ACCESS_POINTS_PREFIX + "bucket", "default-access-point"));
      fileIO.setCredentials(
          List.of(
              StorageCredential.create(
                  prefix,
                  Map.of(
                      S3FileIOProperties.ACCESS_POINTS_PREFIX + "bucket", "scoped-access-point"))));

      PrefixListingPage page = fileIO.listDirectory(prefix).iterator().next();
      assertThat(page.subPrefixes()).containsExactly(prefix + "child/");
      assertThat(fileIO.listDirectory(page.subPrefixes().iterator().next()))
          .singleElement()
          .satisfies(
              childPage -> {
                assertThat(childPage.files()).isEmpty();
                assertThat(childPage.subPrefixes()).isEmpty();
              });
    }
  }

  private void mockListing(String bucket, String prefix, ListObjectsV2Response... responses) {
    ListObjectsV2Request request =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).delimiter("/").build();
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(client.listObjectsV2Paginator(request)).thenReturn(paginator);
    when(paginator.stream()).thenAnswer(invocation -> Arrays.stream(responses));
  }
}
