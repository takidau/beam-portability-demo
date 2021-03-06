/*
 * Copyright 2017 The Project Authors, see separate AUTHORS file
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package demo;

import java.io.Serializable;

import org.apache.avro.reflect.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.io.FileBasedSink.FilenamePolicy;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.ToString;
import org.apache.beam.sdk.transforms.WithTimestamps;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** Compute team scores per hour. */
public class HourlyTeamScore {

  static final Duration ONE_HOUR = Duration.standardMinutes(60);

  public interface Options extends PipelineOptions {

    @Description("Path to the data file(s) containing game data.")
    // The default maps to two large Google Cloud Storage files (each ~12GB) holding two subsequent
    // day's worth (roughly) of data.
    @Default.String("gs://apache-beam-samples/game/gaming_data*.csv")
    String getInput();
    void setInput(String value);

    @Description("Output file prefix")
    @Validation.Required
    String getOutputPrefix();
    void setOutputPrefix(String value);
  }

  /** Class to hold info about a game event. */
  @DefaultCoder(AvroCoder.class)
  static class GameActionInfo implements Serializable {

    @Nullable String user;
    @Nullable String team;
    @Nullable Integer score;
    @Nullable Long timestamp;

    public GameActionInfo() {}

    public GameActionInfo(String user, String team, Integer score, Long timestamp) {
      this.user = user;
      this.team = team;
      this.score = score;
      this.timestamp = timestamp;
    }

    public String getUser() {
      return this.user;
    }
    public String getTeam() {
      return this.team;
    }
    public Integer getScore() {
      return this.score;
    }
    public Long getTimestamp() {
      return this.timestamp;
    }
  }

  /** DoFn that keys the element by the window. */
  public static class KeyByWindowFn
  extends DoFn<KV<String, Integer>, KV<IntervalWindow, KV<String, Integer>>> {
    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext context, IntervalWindow window) {
      context.output(KV.of(window, context.element()));
    }
  }

  /** DoFn to parse raw log lines into structured GameActionInfos. */
  static class ParseEventFn extends DoFn<String, GameActionInfo> {

    // Log and count parse errors.
    private static final Logger LOG = LoggerFactory.getLogger(ParseEventFn.class);
    private static final Counter numParseErrorsCounter = Metrics.counter(ParseEventFn.class, "ParseErrors");

    @ProcessElement
    public void processElement(ProcessContext c) {
      String[] components = c.element().split(",");
      try {
        String user = components[0].trim();
        String team = components[1].trim();
        Integer score = Integer.parseInt(components[2].trim());
        Long timestamp = Long.parseLong(components[3].trim());
        GameActionInfo gInfo = new GameActionInfo(user, team, score, timestamp);
        c.output(gInfo);
      } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
        numParseErrorsCounter.inc();
        LOG.info("Parse error on " + c.element() + ", " + e.getMessage());
      }
    }
  }

  private static class SetTimestampFn
  implements SerializableFunction<String, Instant> {
    @Override
    public Instant apply(String input) {
      String[] components = input.split(",");
      try {
        return new Instant(Long.parseLong(components[3].trim()));
      } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
        return Instant.now();
      }
    }
  }

  /** Key by the team. */
  static class KeyScoreByTeamFn extends DoFn<GameActionInfo, KV<String, Integer>> {

    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext c) {
      c.output(KV.of(c.element().getTeam(), c.element().getScore()));
    }
  }

  /**
   * A {@link FilenamePolicy} produces a base file name for a write based on metadata about the data
   * being written. This always includes the shard number and the total number of shards. For
   * windowed writes, it also includes the window and pane index (a sequence number assigned to each
   * trigger firing).
   */
  public static class PerWindowFiles extends FilenamePolicy {

    private final String prefix;
    private static final DateTimeFormatter FORMATTER = ISODateTimeFormat.hourMinute();

    public PerWindowFiles(String prefix) {
      this.prefix = prefix;
    }

    public String filenamePrefixForWindow(IntervalWindow window) {
      return String.format("%s-%s-%s",
          prefix, FORMATTER.print(window.start()), FORMATTER.print(window.end()));
    }

    @Override
    public ResourceId windowedFilename(
        ResourceId outputDirectory, WindowedContext context, String extension) {
      IntervalWindow window = (IntervalWindow) context.getWindow();
      // TODO(francesperry): Make filename clearly identify early/late firings
      String filename = String.format(
          "%s-%s-of-%s-%s%s",
          filenamePrefixForWindow(window), context.getShardNumber(), context.getNumShards(),
          context.getPaneInfo().getIndex(), extension);
      return outputDirectory.resolve(filename, StandardResolveOptions.RESOLVE_FILE);
    }

    @Override
    public ResourceId unwindowedFilename(
        ResourceId outputDirectory, Context context, String extension) {
      throw new UnsupportedOperationException("Unsupported.");
    }
  }

  /** Takes a collection of GameActionInfo events and writes the sums per team to files. */
  public static class CalculateTeamScores
  extends PTransform<PCollection<String>, PDone> {

    String filepath;

    CalculateTeamScores(String filepath) {
      this.filepath = filepath;
    }

    @Override
    public PDone expand(PCollection<String> line) {

      return line
          .apply("ParseGameEvent", ParDo.of(new ParseEventFn()))
          .apply(ParDo.of(new KeyScoreByTeamFn()))
          .apply(Sum.<String>integersPerKey())
          .apply(ToString.kvs())
          .apply(TextIO.write().to(filepath).withWindowedWrites()
              .withFilenamePolicy(new PerWindowFiles("count")).withNumShards(3));
    }
  }

  /** Run a batch pipeline to calculate hourly team scores. */
  public static void main(String[] args) throws Exception {

    Options options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    Pipeline pipeline = Pipeline.create(options);

    pipeline
    .apply("ReadLogs", TextIO.read().from(options.getInput()))
    .apply("SetTimestamps", WithTimestamps.of(new SetTimestampFn()))

    .apply("FixedWindows", Window.<String>into(FixedWindows.of(ONE_HOUR)))

    .apply("SumTeamScores", new CalculateTeamScores(options.getOutputPrefix()));

    pipeline.run();
  }
}
