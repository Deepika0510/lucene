/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.bkd;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.CollectionUtil;
import org.apache.lucene.util.SuppressForbidden;

public class TestDocIdsWriter extends LuceneTestCase {

  public void testRandom() throws Exception {
    int numIters = atLeast(100);
    try (Directory dir = newDirectory()) {
      for (int iter = 0; iter < numIters; ++iter) {
        int[] docIDs = new int[1 + random().nextInt(5000)];
        final int bpv = TestUtil.nextInt(random(), 1, 32);
        for (int i = 0; i < docIDs.length; ++i) {
          docIDs[i] = TestUtil.nextInt(random(), 0, (1 << bpv) - 1);
        }
        test(dir, docIDs);
      }
    }
  }

  public void testSorted() throws Exception {
    int numIters = atLeast(100);
    try (Directory dir = newDirectory()) {
      for (int iter = 0; iter < numIters; ++iter) {
        int[] docIDs = new int[1 + random().nextInt(5000)];
        final int bpv = TestUtil.nextInt(random(), 1, 32);
        for (int i = 0; i < docIDs.length; ++i) {
          docIDs[i] = TestUtil.nextInt(random(), 0, (1 << bpv) - 1);
        }
        Arrays.sort(docIDs);
        test(dir, docIDs);
      }
    }
  }

  public void testCluster() throws Exception {
    int numIters = atLeast(100);
    try (Directory dir = newDirectory()) {
      for (int iter = 0; iter < numIters; ++iter) {
        int[] docIDs = new int[1 + random().nextInt(5000)];
        int min = random().nextInt(1000);
        final int bpv = TestUtil.nextInt(random(), 1, 16);
        for (int i = 0; i < docIDs.length; ++i) {
          docIDs[i] = min + TestUtil.nextInt(random(), 0, (1 << bpv) - 1);
        }
        test(dir, docIDs);
      }
    }
  }

  public void testBitSet() throws Exception {
    int numIters = atLeast(100);
    try (Directory dir = newDirectory()) {
      for (int iter = 0; iter < numIters; ++iter) {
        int size = 1 + random().nextInt(5000);
        Set<Integer> set = CollectionUtil.newHashSet(size);
        int small = random().nextInt(1000);
        while (set.size() < size) {
          set.add(small + random().nextInt(size * 16));
        }
        int[] docIDs = set.stream().mapToInt(t -> t).sorted().toArray();
        test(dir, docIDs);
      }
    }
  }

  public void testContinuousIds() throws Exception {
    int numIters = atLeast(100);
    try (Directory dir = newDirectory()) {
      for (int iter = 0; iter < numIters; ++iter) {
        int size = 1 + random().nextInt(5000);
        int[] docIDs = new int[size];
        int start = random().nextInt(1000000);
        for (int i = 0; i < docIDs.length; i++) {
          docIDs[i] = start + i;
        }
        test(dir, docIDs);
      }
    }
  }

  private void test(Directory dir, int[] ints) throws Exception {
    final long len;
    DocIdsWriter docIdsWriter = new DocIdsWriter(ints.length);
    try (IndexOutput out = dir.createOutput("tmp", IOContext.DEFAULT)) {
      docIdsWriter.writeDocIds(ints, 0, ints.length, out);
      len = out.getFilePointer();
      if (random().nextBoolean()) {
        out.writeLong(0); // garbage
      }
    }
    try (IndexInput in = dir.openInput("tmp", IOContext.READONCE)) {
      int[] read = new int[ints.length];
      docIdsWriter.readInts(in, ints.length, read);
      assertArrayEquals(ints, read);
      assertEquals(len, in.getFilePointer());
    }
    try (IndexInput in = dir.openInput("tmp", IOContext.READONCE)) {
      int[] read = new int[ints.length];
      docIdsWriter.readInts(
          in,
          ints.length,
          new IntersectVisitor() {
            int i = 0;

            @Override
            public void visit(int docID) throws IOException {
              read[i++] = docID;
            }

            @Override
            public void visit(int docID, byte[] packedValue) throws IOException {
              throw new UnsupportedOperationException();
            }

            @Override
            public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
              throw new UnsupportedOperationException();
            }
          });
      assertArrayEquals(ints, read);
      assertEquals(len, in.getFilePointer());
    }
    dir.deleteFile("tmp");
  }

  // This simple test tickles a JVM C2 JIT crash on JDK's less than 21.0.1
  // Crashes only when run with C2, so with the environment variable `CI` set
  // Regardless of whether C2 is enabled or not, the test should never fail.
  public void testCrash() throws IOException {
    assumeTrue("Requires C2, which is only enabled when CI env is set", getCIEnv() != null);
    int itrs = atLeast(100);
    for (int i = 0; i < itrs; i++) {
      try (Directory dir = newDirectory();
          IndexWriter iw = new IndexWriter(dir, newIndexWriterConfig(null))) {
        for (int d = 0; d < 20_000; d++) {
          iw.addDocument(
              List.of(new IntPoint("foo", 0), new SortedNumericDocValuesField("bar", 0)));
        }
      }
    }
  }

  @SuppressForbidden(reason = "needed to check if C2 is enabled")
  @SuppressWarnings("removal")
  private static String getCIEnv() {
    PrivilegedAction<String> pa = () -> System.getenv("CI");
    return AccessController.doPrivileged(pa);
  }
}
