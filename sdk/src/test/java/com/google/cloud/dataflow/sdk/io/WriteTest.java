/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.io;

import static com.google.cloud.dataflow.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;
import static com.google.cloud.dataflow.sdk.transforms.display.DisplayDataMatchers.includesDisplayDataFrom;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.SerializableCoder;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.io.Sink.WriteOperation;
import com.google.cloud.dataflow.sdk.io.Sink.Writer;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactoryTest.TestPipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.MapElements;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.SimpleFunction;
import com.google.cloud.dataflow.sdk.transforms.display.DisplayData;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Sessions;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for the Write PTransform.
 */
@RunWith(JUnit4.class)
public class WriteTest {
  // Static store that can be accessed within the writer
  private static List<String> sinkContents = new ArrayList<>();
  // Static count of output shards
  private static AtomicInteger numShards = new AtomicInteger(0);
  // Static counts of the number of records per shard.
  private static List<Integer> recordsPerShard = new ArrayList<>();

  private static final MapElements<String, String> IDENTITY_MAP =
      MapElements.via(new SimpleFunction<String, String>() {
        @Override
        public String apply(String input) {
          return input;
        }
      });

  private static class WindowAndReshuffle<T> extends PTransform<PCollection<T>, PCollection<T>> {
    private final Window.Bound<T> window;
    public WindowAndReshuffle(Window.Bound<T> window) {
      this.window = window;
    }

    private static class AddArbitraryKey<T> extends DoFn<T, KV<Integer, T>> {
      @Override
      public void processElement(ProcessContext c) throws Exception {
        c.output(KV.of(ThreadLocalRandom.current().nextInt(), c.element()));
      }
    }

    private static class RemoveArbitraryKey<T> extends DoFn<KV<Integer, Iterable<T>>, T> {
      @Override
      public void processElement(ProcessContext c) throws Exception {
        for (T s : c.element().getValue()) {
          c.output(s);
        }
      }
    }

    @Override
    public PCollection<T> apply(PCollection<T> input) {
      return input
          .apply(window)
          .apply(ParDo.of(new AddArbitraryKey<T>()))
          .apply(GroupByKey.<Integer, T>create())
          .apply(ParDo.of(new RemoveArbitraryKey<T>()));
    }
  }

  /**
   * Test a Write transform with a PCollection of elements.
   */
  @Test
  public void testWrite() {
    List<String> inputs = Arrays.asList("Critical canary", "Apprehensive eagle",
        "Intimidating pigeon", "Pedantic gull", "Frisky finch");
    runWrite(inputs, IDENTITY_MAP);
  }

  /**
   * Test that Write with an empty input still produces one shard.
   */
  @Test
  public void testEmptyWrite() {
    runWrite(Collections.<String>emptyList(), IDENTITY_MAP);
    // Note we did not request a sharded write, so runWrite will not validate the number of shards.
    assertEquals(1, numShards.intValue());
  }

  /**
   * Test that Write with a configured number of shards produces the desired number of shards even
   * when there are many elements.
   */
  @Test
  public void testShardedWrite() {
    runShardedWrite(
        Arrays.asList("one", "two", "three", "four", "five", "six"),
        IDENTITY_MAP,
        Optional.of(1));
  }

  /**
   * Test that Write with a configured number of shards produces the desired number of shards even
   * when there are too few elements.
   */
  @Test
  public void testExpandShardedWrite() {
    runShardedWrite(
        Arrays.asList("one", "two", "three", "four", "five", "six"),
        IDENTITY_MAP,
        Optional.of(20));
  }

  /**
   * Tests that a Write can balance many elements.
   */
  @Test
  public void testShardedWriteBalanced() {
    int numElements = 1000;
    List<String> inputs = new ArrayList<>(numElements);
    for (int i = 0; i < numElements; ++i) {
      inputs.add(String.format("elt%04d", i));
    }

    runShardedWrite(
        inputs,
        new WindowAndReshuffle<>(
            Window.<String>into(Sessions.withGapDuration(Duration.millis(1)))),
        Optional.of(10));

    // Check that both the min and max number of results per shard are close to the expected.
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;
    for (Integer i : recordsPerShard) {
      min = Math.min(min, i);
      max = Math.max(max, i);
    }
    assertThat((double) min, Matchers.greaterThanOrEqualTo(max * 0.9));
  }

  /**
   * Test a Write transform with an empty PCollection.
   */
  @Test
  public void testWriteWithEmptyPCollection() {
    List<String> inputs = new ArrayList<>();
    runWrite(inputs, IDENTITY_MAP);
  }

  /**
   * Test a Write with a windowed PCollection.
   */
  @Test
  public void testWriteWindowed() {
    List<String> inputs = Arrays.asList("Critical canary", "Apprehensive eagle",
        "Intimidating pigeon", "Pedantic gull", "Frisky finch");
    runWrite(
        inputs, new WindowAndReshuffle<>(Window.<String>into(FixedWindows.of(Duration.millis(2)))));
  }

  /**
   * Test a Write with sessions.
   */
  @Test
  public void testWriteWithSessions() {
    List<String> inputs = Arrays.asList("Critical canary", "Apprehensive eagle",
        "Intimidating pigeon", "Pedantic gull", "Frisky finch");

    runWrite(
        inputs,
        new WindowAndReshuffle<>(
            Window.<String>into(Sessions.withGapDuration(Duration.millis(1)))));
  }

  @Test
  public void testBuildWrite() {
    Sink<String> sink = new TestSink() {};
    Write.Bound<String> write = Write.to(sink).withNumShards(3);
    assertEquals(3, write.getNumShards());
    assertThat(write.getSink(), is(sink));

    Write.Bound<String> write2 = write.withNumShards(7);
    assertEquals(7, write2.getNumShards());
    assertThat(write2.getSink(), is(sink));
    // original unchanged
    assertEquals(3, write.getNumShards());
  }

  @Test
  public void testDisplayData() {
    TestSink sink = new TestSink() {
      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        builder.add(DisplayData.item("foo", "bar"));
      }
    };
    Write.Bound<String> write = Write.to(sink);
    DisplayData displayData = DisplayData.from(write);

    assertThat(displayData, hasDisplayItem("sink", sink.getClass()));
    assertThat(displayData, includesDisplayDataFrom(sink));
  }

  @Test
  public void testShardedDisplayData() {
    TestSink sink = new TestSink() {
      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        builder.add(DisplayData.item("foo", "bar"));
      }
    };
    Write.Bound<String> write = Write.to(sink).withNumShards(1);
    DisplayData displayData = DisplayData.from(write);
    assertThat(displayData, hasDisplayItem("sink", sink.getClass()));
    assertThat(displayData, includesDisplayDataFrom(sink));
    assertThat(displayData, hasDisplayItem("numShards", 1));
  }

  /**
   * Performs a Write transform and verifies the Write transform calls the appropriate methods on
   * a test sink in the correct order, as well as verifies that the elements of a PCollection are
   * written to the sink.
   */
  private static void runWrite(
      List<String> inputs, PTransform<PCollection<String>, PCollection<String>> transform) {
    runShardedWrite(inputs, transform, Optional.<Integer>absent());
  }

  /**
   * Performs a Write transform with the desired number of shards. Verifies the Write transform
   * calls the appropriate methods on a test sink in the correct order, as well as verifies that
   * the elements of a PCollection are written to the sink. If numConfiguredShards is not null, also
   * verifies that the output number of shards is correct.
   */
  private static void runShardedWrite(
      List<String> inputs, PTransform<PCollection<String>, PCollection<String>> transform,
      Optional<Integer> numConfiguredShards) {
    // Flag to validate that the pipeline options are passed to the Sink
    String[] args = {"--testFlag=test_value"};
    PipelineOptions options = PipelineOptionsFactory.fromArgs(args).as(WriteOptions.class);
    Pipeline p = Pipeline.create(options);

    // Clear the sink's contents.
    sinkContents.clear();
    // Reset the number of shards produced.
    numShards.set(0);
    // Reset the number of records in each shard.
    recordsPerShard.clear();

    // Prepare timestamps for the elements.
    List<Long> timestamps = new ArrayList<>();
    for (long i = 0; i < inputs.size(); i++) {
      timestamps.add(i + 1);
    }

    TestSink sink = new TestSink();
    Write.Bound<String> write = Write.to(sink);
    if (numConfiguredShards.isPresent()) {
      write = write.withNumShards(numConfiguredShards.get());
    }
    p.apply(Create.timestamped(inputs, timestamps).withCoder(StringUtf8Coder.of()))
     .apply(transform)
     .apply(write);

    p.run();
    assertThat(sinkContents, containsInAnyOrder(inputs.toArray()));
    assertTrue(sink.hasCorrectState());
    if (numConfiguredShards.isPresent()) {
      assertEquals(numConfiguredShards.get().intValue(), numShards.intValue());
      assertEquals(numConfiguredShards.get().intValue(), recordsPerShard.size());
    }
  }

  // Test sink and associated write operation and writer. TestSink, TestWriteOperation, and
  // TestWriter each verify that the sequence of method calls is consistent with the specification
  // of the Write PTransform.
  private static class TestSink extends Sink<String> {
    private boolean createCalled = false;
    private boolean validateCalled = false;

    @Override
    public WriteOperation<String, ?> createWriteOperation(PipelineOptions options) {
      assertTrue(validateCalled);
      assertTestFlagPresent(options);
      createCalled = true;
      return new TestSinkWriteOperation(this);
    }

    @Override
    public void validate(PipelineOptions options) {
      assertTestFlagPresent(options);
      validateCalled = true;
    }

    private void assertTestFlagPresent(PipelineOptions options) {
      assertEquals("test_value", options.as(WriteOptions.class).getTestFlag());
    }

    private boolean hasCorrectState() {
      return validateCalled && createCalled;
    }

    /**
     * Implementation of equals() that indicates all test sinks are equal.
     */
    @Override
    public boolean equals(Object other) {
      return (other instanceof TestSink);
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("createCalled", createCalled)
          .add("validateCalled", validateCalled)
          .toString();
    }
  }

  private static class TestSinkWriteOperation extends WriteOperation<String, TestWriterResult> {
    private enum State {
      INITIAL,
      INITIALIZED,
      FINALIZED
    }

    // Must be static in case the WriteOperation is serialized before the its coder is obtained.
    // If this occurs, the value will be modified but not reflected in the WriteOperation that is
    // executed by the runner, and the finalize method will fail.
    private static volatile boolean coderCalled = false;

    private State state = State.INITIAL;

    private final TestSink sink;
    private final UUID id = UUID.randomUUID();

    public TestSinkWriteOperation(TestSink sink) {
      this.sink = sink;
    }

    @Override
    public TestSink getSink() {
      return sink;
    }

    @Override
    public void initialize(PipelineOptions options) throws Exception {
      assertEquals("test_value", options.as(WriteOptions.class).getTestFlag());
      assertThat(state, anyOf(equalTo(State.INITIAL), equalTo(State.INITIALIZED)));
      state = State.INITIALIZED;
    }

    @Override
    public void finalize(Iterable<TestWriterResult> bundleResults, PipelineOptions options)
        throws Exception {
      assertEquals("test_value", options.as(WriteOptions.class).getTestFlag());
      assertEquals(State.INITIALIZED, state);
      // The coder for the test writer results should've been called.
      assertTrue(coderCalled);
      Set<String> idSet = new HashSet<>();
      int resultCount = 0;
      state = State.FINALIZED;
      for (TestWriterResult result : bundleResults) {
        resultCount += 1;
        idSet.add(result.uId);
        // Add the elements that were written to the sink's contents.
        sinkContents.addAll(result.elementsWritten);
        recordsPerShard.add(result.elementsWritten.size());
      }
      // Each result came from a unique id.
      assertEquals(resultCount, idSet.size());
    }

    @Override
    public Writer<String, TestWriterResult> createWriter(PipelineOptions options) {
      return new TestSinkWriter(this);
    }

    @Override
    public Coder<TestWriterResult> getWriterResultCoder() {
      coderCalled = true;
      return SerializableCoder.of(TestWriterResult.class);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("id", id)
          .add("sink", sink)
          .add("state", state)
          .add("coderCalled", coderCalled)
          .toString();
    }

    /**
     * Implementation of equals() that does not depend on the state of the write operation,
     * but only its specification. In general, write operations will have interesting
     * specifications, but for a {@link TestSinkWriteOperation}, it is not the case. Instead,
     * a unique identifier (that is serialized along with it) is used to simulate such a
     * specification.
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof TestSinkWriteOperation)) {
        return false;
      }
      TestSinkWriteOperation otherOperation = (TestSinkWriteOperation) other;
      return sink.equals(otherOperation.sink)
          && id.equals(otherOperation.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, sink);
    }
  }

  private static class TestWriterResult implements Serializable {
    String uId;
    List<String> elementsWritten;

    public TestWriterResult(String uId, List<String> elementsWritten) {
      this.uId = uId;
      this.elementsWritten = elementsWritten;
    }
  }

  private static class TestSinkWriter extends Writer<String, TestWriterResult> {
    private enum State {
      INITIAL,
      OPENED,
      WRITING,
      CLOSED
    }

    private State state = State.INITIAL;
    private List<String> elementsWritten = new ArrayList<>();
    private String uId;

    private final TestSinkWriteOperation writeOperation;

    public TestSinkWriter(TestSinkWriteOperation writeOperation) {
      this.writeOperation = writeOperation;
    }

    @Override
    public TestSinkWriteOperation getWriteOperation() {
      return writeOperation;
    }

    @Override
    public void open(String uId) throws Exception {
      numShards.incrementAndGet();
      this.uId = uId;
      assertEquals(State.INITIAL, state);
      state = State.OPENED;
    }

    @Override
    public void write(String value) throws Exception {
      assertThat(state, anyOf(equalTo(State.OPENED), equalTo(State.WRITING)));
      state = State.WRITING;
      elementsWritten.add(value);
    }

    @Override
    public TestWriterResult close() throws Exception {
      assertThat(state, anyOf(equalTo(State.OPENED), equalTo(State.WRITING)));
      state = State.CLOSED;
      return new TestWriterResult(uId, elementsWritten);
    }
  }

  /**
   * Options for test, exposed for PipelineOptionsFactory.
   */
  public interface WriteOptions extends TestPipelineOptions {
    @Description("Test flag and value")
    String getTestFlag();
    void setTestFlag(String value);
  }
}
