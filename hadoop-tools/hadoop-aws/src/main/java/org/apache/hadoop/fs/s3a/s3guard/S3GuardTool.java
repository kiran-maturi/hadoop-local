/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.s3guard;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.s3a.S3AFileStatus;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.S3AUtils;
import org.apache.hadoop.fs.s3a.impl.DirectoryPolicy;
import org.apache.hadoop.fs.shell.CommandFormat;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import static org.apache.hadoop.fs.s3a.Constants.*;
import static org.apache.hadoop.service.launcher.LauncherExitCodes.*;

/**
 * CLI to manage S3Guard Metadata Store.
 */
@InterfaceAudience.LimitedPrivate("management tools")
@InterfaceStability.Evolving
public abstract class S3GuardTool extends Configured implements Tool {
  private static final Logger LOG = LoggerFactory.getLogger(S3GuardTool.class);

  private static final String NAME = "s3guard";
  private static final String COMMON_USAGE =
      "When possible and not overridden by more specific options, metadata\n" +
      "repository information will be inferred from the S3A URL (if provided)" +
      "\n\n" +
      "Generic options supported are:\n" +
      "  -conf <config file> - specify an application configuration file\n" +
      "  -D <property=value> - define a value for a given property\n";

  private static final String USAGE = NAME +
      " [command] [OPTIONS] [s3a://BUCKET]\n\n" +
      "Commands: \n" +
      "\t" + Init.NAME + " - " + Init.PURPOSE + "\n" +
      "\t" + Destroy.NAME + " - " + Destroy.PURPOSE + "\n" +
      "\t" + Import.NAME + " - " + Import.PURPOSE + "\n" +
      "\t" + BucketInfo.NAME + " - " + BucketInfo.PURPOSE + "\n" +
      "\t" + Diff.NAME + " - " + Diff.PURPOSE + "\n" +
      "\t" + Prune.NAME + " - " + Prune.PURPOSE + "\n" +
      "\t" + SetCapacity.NAME + " - " +SetCapacity.PURPOSE + "\n";
  private static final String DATA_IN_S3_IS_PRESERVED
      = "(all data in S3 is preserved)";

  abstract public String getUsage();

  // Exit codes
  static final int SUCCESS = EXIT_SUCCESS;
  static final int INVALID_ARGUMENT = EXIT_COMMAND_ARGUMENT_ERROR;
  static final int E_USAGE = EXIT_USAGE;
  static final int ERROR = EXIT_FAIL;
  static final int E_BAD_STATE = EXIT_NOT_ACCEPTABLE;
  static final int E_NOT_FOUND = EXIT_NOT_FOUND;

  private S3AFileSystem filesystem;
  private MetadataStore store;
  private final CommandFormat commandFormat;

  public static final String META_FLAG = "meta";
  public static final String DAYS_FLAG = "days";
  public static final String HOURS_FLAG = "hours";
  public static final String MINUTES_FLAG = "minutes";
  public static final String SECONDS_FLAG = "seconds";

  public static final String REGION_FLAG = "region";
  public static final String READ_FLAG = "read";
  public static final String WRITE_FLAG = "write";

  /**
   * Constructor a S3Guard tool with HDFS configuration.
   * @param conf Configuration.
   * @param opts any boolean options to support
   */
  protected S3GuardTool(Configuration conf, String...opts) {
    super(conf);

    commandFormat = new CommandFormat(0, Integer.MAX_VALUE, opts);
    // For metadata store URI
    commandFormat.addOptionWithValue(META_FLAG);
    // DDB region.
    commandFormat.addOptionWithValue(REGION_FLAG);
  }

  /**
   * Return sub-command name.
   */
  abstract String getName();

  /**
   * Parse DynamoDB region from either -m option or a S3 path.
   *
   * This function should only be called from {@link Init} or
   * {@link Destroy}.
   *
   * @param paths remaining parameters from CLI.
   * @throws IOException on I/O errors.
   * @throws ExitUtil.ExitException on validation errors
   */
  void parseDynamoDBRegion(List<String> paths) throws IOException {
    Configuration conf = getConf();
    String fromCli = getCommandFormat().getOptValue(REGION_FLAG);
    String fromConf = conf.get(S3GUARD_DDB_REGION_KEY);
    boolean hasS3Path = !paths.isEmpty();

    if (fromCli != null) {
      if (fromCli.isEmpty()) {
        throw invalidArgs("No region provided with -" + REGION_FLAG + " flag");
      }
      if (hasS3Path) {
        throw invalidArgs("Providing both an S3 path and the"
            + " -" + REGION_FLAG
            + " flag is not supported. If you need to specify a different "
            + "region than the S3 bucket, configure " + S3GUARD_DDB_REGION_KEY);
      }
      conf.set(S3GUARD_DDB_REGION_KEY, fromCli);
      return;
    }

    if (fromConf != null) {
      if (fromConf.isEmpty()) {
        throw invalidArgs("No region provided with config %s",
            S3GUARD_DDB_REGION_KEY);
      }
      return;
    }

    if (hasS3Path) {
      String s3Path = paths.get(0);
      initS3AFileSystem(s3Path);
      return;
    }

    throw invalidArgs("No region found from -" + REGION_FLAG + " flag, " +
        "config, or S3 bucket");
  }

  /**
   * Parse metadata store from command line option or HDFS configuration.
   *
   * @param forceCreate override the auto-creation setting to true.
   * @return a initialized metadata store.
   */
  MetadataStore initMetadataStore(boolean forceCreate) throws IOException {
    if (getStore() != null) {
      return getStore();
    }
    Configuration conf;
    if (filesystem == null) {
      conf = getConf();
    } else {
      conf = filesystem.getConf();
    }
    String metaURI = getCommandFormat().getOptValue(META_FLAG);
    if (metaURI != null && !metaURI.isEmpty()) {
      URI uri = URI.create(metaURI);
      LOG.info("Create metadata store: {}", uri + " scheme: "
          + uri.getScheme());
      switch (uri.getScheme().toLowerCase(Locale.ENGLISH)) {
      case "local":
        setStore(new LocalMetadataStore());
        break;
      case "dynamodb":
        setStore(new DynamoDBMetadataStore());
        conf.set(S3GUARD_DDB_TABLE_NAME_KEY, uri.getAuthority());
        if (forceCreate) {
          conf.setBoolean(S3GUARD_DDB_TABLE_CREATE_KEY, true);
        }
        break;
      default:
        throw new IOException(
            String.format("Metadata store %s is not supported", uri));
      }
    } else {
      // CLI does not specify metadata store URI, it uses default metadata store
      // DynamoDB instead.
      setStore(new DynamoDBMetadataStore());
      if (forceCreate) {
        conf.setBoolean(S3GUARD_DDB_TABLE_CREATE_KEY, true);
      }
    }

    if (filesystem == null) {
      getStore().initialize(conf);
    } else {
      getStore().initialize(filesystem);
    }
    LOG.info("Metadata store {} is initialized.", getStore());
    return getStore();
  }

  /**
   * Create and initialize a new S3A FileSystem instance.
   * This instance is always created without S3Guard, so allowing
   * a previously created metastore to be patched in.
   *
   * Note: this is a bit convoluted as it needs to also handle the situation
   * of a per-bucket option in core-site.xml, which isn't easily overridden.
   * The new config and the setting of the values before any
   * {@code Configuration.get()} calls are critical.
   *
   * @param path s3a URI
   * @throws IOException failure to init filesystem
   * @throws ExitUtil.ExitException if the FS is not an S3A FS
   */
  void initS3AFileSystem(String path) throws IOException {
    URI uri = toUri(path);
    // Make sure that S3AFileSystem does not hold an actual MetadataStore
    // implementation.
    Configuration conf = new Configuration(getConf());
    String nullStore = NullMetadataStore.class.getName();
    conf.set(S3_METADATA_STORE_IMPL, nullStore);
    String bucket = uri.getHost();
    S3AUtils.setBucketOption(conf,
        bucket,
        S3_METADATA_STORE_IMPL, S3GUARD_METASTORE_NULL);
    String updatedBucketOption = S3AUtils.getBucketOption(conf, bucket,
        S3_METADATA_STORE_IMPL);
    LOG.debug("updated bucket store option {}", updatedBucketOption);
    Preconditions.checkState(S3GUARD_METASTORE_NULL.equals(updatedBucketOption),
        "Expected bucket option to be %s but was %s",
        S3GUARD_METASTORE_NULL, updatedBucketOption);

    FileSystem fs = FileSystem.newInstance(uri, conf);
    if (!(fs instanceof S3AFileSystem)) {
      throw invalidArgs("URI %s is not a S3A file system: %s",
          uri, fs.getClass().getName());
    }
    filesystem = (S3AFileSystem) fs;
  }

  /**
   * Parse CLI arguments and returns the position arguments.
   * The options are stored in {@link #commandFormat}.
   *
   * @param args command line arguments.
   * @return the position arguments from CLI.
   */
  List<String> parseArgs(String[] args) {
    return getCommandFormat().parse(args, 1);
  }

  protected S3AFileSystem getFilesystem() {
    return filesystem;
  }

  protected void setFilesystem(S3AFileSystem filesystem) {
    this.filesystem = filesystem;
  }

  @VisibleForTesting
  public MetadataStore getStore() {
    return store;
  }

  @VisibleForTesting
  protected void setStore(MetadataStore store) {
    Preconditions.checkNotNull(store);
    this.store = store;
  }

  protected CommandFormat getCommandFormat() {
    return commandFormat;
  }

  @Override
  public final int run(String[] args) throws Exception {
    return run(args, System.out);
  }

  /**
   * Run the tool, capturing the output (if the tool supports that).
   *
   * As well as returning an exit code, the implementations can choose to
   * throw an instance of {@link ExitUtil.ExitException} with their exit
   * code set to the desired exit value. The exit code of auch an exception
   * is used for the tool's exit code, and the stack trace only logged at
   * debug.
   * @param args argument list
   * @param out output stream
   * @return the exit code to return.
   * @throws Exception on any failure
   * @throws ExitUtil.ExitException for an alternative clean exit
   */
  public abstract int run(String[] args, PrintStream out) throws Exception;

  /**
   * Create the metadata store.
   */
  static class Init extends S3GuardTool {
    public static final String NAME = "init";
    public static final String PURPOSE = "initialize metadata repository";
    private static final String USAGE = NAME + " [OPTIONS] [s3a://BUCKET]\n" +
        "\t" + PURPOSE + "\n\n" +
        "Common options:\n" +
        "  -" + META_FLAG + " URL - Metadata repository details " +
          "(implementation-specific)\n" +
        "\n" +
        "Amazon DynamoDB-specific options:\n" +
        "  -" + REGION_FLAG + " REGION - Service region for connections\n" +
        "  -" + READ_FLAG + " UNIT - Provisioned read throughput units\n" +
        "  -" + WRITE_FLAG + " UNIT - Provisioned write through put units\n" +
        "\n" +
        "  URLs for Amazon DynamoDB are of the form dynamodb://TABLE_NAME.\n" +
        "  Specifying both the -" + REGION_FLAG + " option and an S3A path\n" +
        "  is not supported.";

    Init(Configuration conf) {
      super(conf);
      // read capacity.
      getCommandFormat().addOptionWithValue(READ_FLAG);
      // write capacity.
      getCommandFormat().addOptionWithValue(WRITE_FLAG);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    @Override
    public int run(String[] args, PrintStream out) throws Exception {
      List<String> paths = parseArgs(args);

      String readCap = getCommandFormat().getOptValue(READ_FLAG);
      if (readCap != null && !readCap.isEmpty()) {
        int readCapacity = Integer.parseInt(readCap);
        getConf().setInt(S3GUARD_DDB_TABLE_CAPACITY_READ_KEY, readCapacity);
      }
      String writeCap = getCommandFormat().getOptValue(WRITE_FLAG);
      if (writeCap != null && !writeCap.isEmpty()) {
        int writeCapacity = Integer.parseInt(writeCap);
        getConf().setInt(S3GUARD_DDB_TABLE_CAPACITY_WRITE_KEY, writeCapacity);
      }

      // Validate parameters.
      try {
        parseDynamoDBRegion(paths);
      } catch (ExitUtil.ExitException e) {
        errorln(USAGE);
        throw e;
      }
      MetadataStore store = initMetadataStore(true);
      printStoreDiagnostics(out, store);
      return SUCCESS;
    }
  }

  /**
   * Change the capacity of the metadata store.
   */
  static class SetCapacity extends S3GuardTool {
    public static final String NAME = "set-capacity";
    public static final String PURPOSE = "Alter metadata store IO capacity";
    private static final String USAGE = NAME + " [OPTIONS] [s3a://BUCKET]\n" +
        "\t" + PURPOSE + "\n\n" +
        "Common options:\n" +
        "  -" + META_FLAG + " URL - Metadata repository details " +
          "(implementation-specific)\n" +
        "\n" +
        "Amazon DynamoDB-specific options:\n" +
        "  -" + READ_FLAG + " UNIT - Provisioned read throughput units\n" +
        "  -" + WRITE_FLAG + " UNIT - Provisioned write through put units\n" +
        "\n" +
        "  URLs for Amazon DynamoDB are of the form dynamodb://TABLE_NAME.\n" +
        "  Specifying both the -" + REGION_FLAG + " option and an S3A path\n" +
        "  is not supported.";

    SetCapacity(Configuration conf) {
      super(conf);
      // read capacity.
      getCommandFormat().addOptionWithValue(READ_FLAG);
      // write capacity.
      getCommandFormat().addOptionWithValue(WRITE_FLAG);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    @Override
    public int run(String[] args, PrintStream out) throws Exception {
      List<String> paths = parseArgs(args);
      Map<String, String> options = new HashMap<>();

      String readCap = getCommandFormat().getOptValue(READ_FLAG);
      if (StringUtils.isNotEmpty(readCap)) {
        S3GuardTool.println(out, "Read capacity set to %s", readCap);
        options.put(S3GUARD_DDB_TABLE_CAPACITY_READ_KEY, readCap);
      }
      String writeCap = getCommandFormat().getOptValue(WRITE_FLAG);
      if (StringUtils.isNotEmpty(writeCap)) {
        S3GuardTool.println(out, "Write capacity set to %s", writeCap);
        options.put(S3GUARD_DDB_TABLE_CAPACITY_WRITE_KEY, writeCap);
      }

      // Validate parameters.
      try {
        parseDynamoDBRegion(paths);
      } catch (ExitUtil.ExitException e) {
        errorln(USAGE);
        throw e;
      }
      MetadataStore store = initMetadataStore(false);
      store.updateParameters(options);
      printStoreDiagnostics(out, store);
      return SUCCESS;
    }
  }


  /**
   * Destroy a metadata store.
   */
  static class Destroy extends S3GuardTool {
    public static final String NAME = "destroy";
    public static final String PURPOSE = "destroy Metadata Store data "
        + DATA_IN_S3_IS_PRESERVED;
    private static final String USAGE = NAME + " [OPTIONS] [s3a://BUCKET]\n" +
        "\t" + PURPOSE + "\n\n" +
        "Common options:\n" +
        "  -" + META_FLAG + " URL - Metadata repository details " +
          "(implementation-specific)\n" +
        "\n" +
        "Amazon DynamoDB-specific options:\n" +
        "  -" + REGION_FLAG + " REGION - Service region for connections\n" +
        "\n" +
        "  URLs for Amazon DynamoDB are of the form dynamodb://TABLE_NAME.\n" +
        "  Specifying both the -" + REGION_FLAG + " option and an S3A path\n" +
        "  is not supported.";

    Destroy(Configuration conf) {
      super(conf);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    public int run(String[] args, PrintStream out) throws Exception {
      List<String> paths = parseArgs(args);
      try {
        parseDynamoDBRegion(paths);
      } catch (ExitUtil.ExitException e) {
        errorln(USAGE);
        throw e;
      }

      try {
        initMetadataStore(false);
      } catch (FileNotFoundException e) {
        // indication that the table was not found
        println(out, "Metadata Store does not exist.");
        LOG.debug("Failed to bind to store to be destroyed", e);
        return SUCCESS;
      }

      Preconditions.checkState(getStore() != null,
          "Metadata Store is not initialized");

      getStore().destroy();
      println(out, "Metadata store is deleted.");
      return SUCCESS;
    }
  }

  /**
   * Import s3 metadata to the metadata store.
   */
  static class Import extends S3GuardTool {
    public static final String NAME = "import";
    public static final String PURPOSE = "import metadata from existing S3 " +
        "data";
    private static final String USAGE = NAME + " [OPTIONS] [s3a://BUCKET]\n" +
        "\t" + PURPOSE + "\n\n" +
        "Common options:\n" +
        "  -" + META_FLAG + " URL - Metadata repository details " +
        "(implementation-specific)\n" +
        "\n" +
        "Amazon DynamoDB-specific options:\n" +
        "  -" + REGION_FLAG + " REGION - Service region for connections\n" +
        "\n" +
        "  URLs for Amazon DynamoDB are of the form dynamodb://TABLE_NAME.\n" +
        "  Specifying both the -" + REGION_FLAG + " option and an S3A path\n" +
        "  is not supported.";

    private final Set<Path> dirCache = new HashSet<>();

    Import(Configuration conf) {
      super(conf);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    /**
     * Put parents into MS and cache if the parents are not presented.
     *
     * @param f the file or an empty directory.
     * @throws IOException on I/O errors.
     */
    private void putParentsIfNotPresent(FileStatus f) throws IOException {
      Preconditions.checkNotNull(f);
      Path parent = f.getPath().getParent();
      while (parent != null) {
        if (dirCache.contains(parent)) {
          return;
        }
        FileStatus dir = DynamoDBMetadataStore.makeDirStatus(parent,
            f.getOwner());
        getStore().put(new PathMetadata(dir));
        dirCache.add(parent);
        parent = parent.getParent();
      }
    }

    /**
     * Recursively import every path under path.
     * @return number of items inserted into MetadataStore
     * @throws IOException on I/O errors.
     */
    private long importDir(FileStatus status) throws IOException {
      Preconditions.checkArgument(status.isDirectory());
      RemoteIterator<LocatedFileStatus> it = getFilesystem()
          .listFilesAndEmptyDirectories(status.getPath(), true);
      long items = 0;

      while (it.hasNext()) {
        LocatedFileStatus located = it.next();
        FileStatus child;
        if (located.isDirectory()) {
          child = DynamoDBMetadataStore.makeDirStatus(located.getPath(),
              located.getOwner());
          dirCache.add(child.getPath());
        } else {
          child = new S3AFileStatus(located.getLen(),
              located.getModificationTime(),
              located.getPath(),
              located.getBlockSize(),
              located.getOwner());
        }
        putParentsIfNotPresent(child);
        getStore().put(new PathMetadata(child));
        items++;
      }
      return items;
    }

    @Override
    public int run(String[] args, PrintStream out) throws Exception {
      List<String> paths = parseArgs(args);
      if (paths.isEmpty()) {
        errorln(getUsage());
        throw invalidArgs("no arguments");
      }
      String s3Path = paths.get(0);
      initS3AFileSystem(s3Path);

      URI uri = toUri(s3Path);
      String filePath = uri.getPath();
      if (filePath.isEmpty()) {
        // If they specify a naked S3 URI (e.g. s3a://bucket), we'll consider
        // root to be the path
        filePath = "/";
      }
      Path path = new Path(filePath);
      FileStatus status = getFilesystem().getFileStatus(path);

      try {
        initMetadataStore(false);
      } catch (FileNotFoundException e) {
        throw storeNotFound(e);
      }

      long items = 1;
      if (status.isFile()) {
        PathMetadata meta = new PathMetadata(status);
        getStore().put(meta);
      } else {
        items = importDir(status);
      }

      println(out, "Inserted %d items into Metadata Store", items);

      return SUCCESS;
    }

  }

  /**
   * Show diffs between the s3 and metadata store.
   */
  static class Diff extends S3GuardTool {
    public static final String NAME = "diff";
    public static final String PURPOSE = "report on delta between S3 and " +
        "repository";
    private static final String USAGE = NAME + " [OPTIONS] s3a://BUCKET\n" +
        "\t" + PURPOSE + "\n\n" +
        "Common options:\n" +
        "  -" + META_FLAG + " URL - Metadata repository details " +
        "(implementation-specific)\n" +
        "\n" +
        "Amazon DynamoDB-specific options:\n" +
        "  -" + REGION_FLAG + " REGION - Service region for connections\n" +
        "\n" +
        "  URLs for Amazon DynamoDB are of the form dynamodb://TABLE_NAME.\n" +
        "  Specifying both the -" + REGION_FLAG + " option and an S3A path\n" +
        "  is not supported.";

    private static final String SEP = "\t";
    static final String S3_PREFIX = "S3";
    static final String MS_PREFIX = "MS";

    Diff(Configuration conf) {
      super(conf);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    /**
     * Formats the output of printing a FileStatus in S3guard diff tool.
     * @param status the status to print.
     * @return the string of output.
     */
    private static String formatFileStatus(FileStatus status) {
      return String.format("%s%s%d%s%s",
          status.isDirectory() ? "D" : "F",
          SEP,
          status.getLen(),
          SEP,
          status.getPath().toString());
    }

    /**
     * Compares metadata from 2 S3 FileStatus's to see if they differ.
     * @param thisOne
     * @param thatOne
     * @return true if the metadata is not identical
     */
    private static boolean differ(FileStatus thisOne, FileStatus thatOne) {
      Preconditions.checkArgument(!(thisOne == null && thatOne == null));
      return (thisOne == null || thatOne == null) ||
          (thisOne.getLen() != thatOne.getLen()) ||
          (thisOne.isDirectory() != thatOne.isDirectory()) ||
          (!thisOne.isDirectory() &&
              thisOne.getModificationTime() != thatOne.getModificationTime());
    }

    /**
     * Print difference, if any, between two file statuses to the output stream.
     *
     * @param msStatus file status from metadata store.
     * @param s3Status file status from S3.
     * @param out output stream.
     */
    private static void printDiff(FileStatus msStatus,
                                  FileStatus s3Status,
                                  PrintStream out) {
      Preconditions.checkArgument(!(msStatus == null && s3Status == null));
      if (msStatus != null && s3Status != null) {
        Preconditions.checkArgument(
            msStatus.getPath().equals(s3Status.getPath()),
            String.format("The path from metadata store and s3 are different:" +
            " ms=%s s3=%s", msStatus.getPath(), s3Status.getPath()));
      }

      if (differ(msStatus, s3Status)) {
        if (s3Status != null) {
          println(out, "%s%s%s", S3_PREFIX, SEP, formatFileStatus(s3Status));
        }
        if (msStatus != null) {
          println(out, "%s%s%s", MS_PREFIX, SEP, formatFileStatus(msStatus));
        }
      }
    }

    /**
     * Compare the metadata of the directory with the same path, on S3 and
     * the metadata store, respectively. If one of them is null, consider the
     * metadata of the directory and all its subdirectories are missing from
     * the source.
     *
     * Pass the FileStatus obtained from s3 and metadata store to avoid one
     * round trip to fetch the same metadata twice, because the FileStatus
     * hve already been obtained from listStatus() / listChildren operations.
     *
     * @param msDir the directory FileStatus obtained from the metadata store.
     * @param s3Dir the directory FileStatus obtained from S3.
     * @param out the output stream to generate diff results.
     * @throws IOException on I/O errors.
     */
    private void compareDir(FileStatus msDir, FileStatus s3Dir,
                            PrintStream out) throws IOException {
      Preconditions.checkArgument(!(msDir == null && s3Dir == null));
      if (msDir != null && s3Dir != null) {
        Preconditions.checkArgument(msDir.getPath().equals(s3Dir.getPath()),
            String.format("The path from metadata store and s3 are different:" +
             " ms=%s s3=%s", msDir.getPath(), s3Dir.getPath()));
      }

      Map<Path, FileStatus> s3Children = new HashMap<>();
      if (s3Dir != null && s3Dir.isDirectory()) {
        for (FileStatus status : getFilesystem().listStatus(s3Dir.getPath())) {
          s3Children.put(status.getPath(), status);
        }
      }

      Map<Path, FileStatus> msChildren = new HashMap<>();
      if (msDir != null && msDir.isDirectory()) {
        DirListingMetadata dirMeta =
            getStore().listChildren(msDir.getPath());

        if (dirMeta != null) {
          for (PathMetadata meta : dirMeta.getListing()) {
            FileStatus status = meta.getFileStatus();
            msChildren.put(status.getPath(), status);
          }
        }
      }

      Set<Path> allPaths = new HashSet<>(s3Children.keySet());
      allPaths.addAll(msChildren.keySet());

      for (Path path : allPaths) {
        FileStatus s3Status = s3Children.get(path);
        FileStatus msStatus = msChildren.get(path);
        printDiff(msStatus, s3Status, out);
        if ((s3Status != null && s3Status.isDirectory()) ||
            (msStatus != null && msStatus.isDirectory())) {
          compareDir(msStatus, s3Status, out);
        }
      }
      out.flush();
    }

    /**
     * Compare both metadata store and S3 on the same path.
     *
     * @param path the path to be compared.
     * @param out  the output stream to display results.
     * @throws IOException on I/O errors.
     */
    private void compareRoot(Path path, PrintStream out) throws IOException {
      Path qualified = getFilesystem().qualify(path);
      FileStatus s3Status = null;
      try {
        s3Status = getFilesystem().getFileStatus(qualified);
      } catch (FileNotFoundException e) {
        /* ignored */
      }
      PathMetadata meta = getStore().get(qualified);
      FileStatus msStatus = (meta != null && !meta.isDeleted()) ?
          meta.getFileStatus() : null;
      compareDir(msStatus, s3Status, out);
    }

    @VisibleForTesting
    public int run(String[] args, PrintStream out) throws IOException {
      List<String> paths = parseArgs(args);
      if (paths.isEmpty()) {
        out.println(USAGE);
        throw invalidArgs("no arguments");
      }
      String s3Path = paths.get(0);
      initS3AFileSystem(s3Path);
      initMetadataStore(false);

      URI uri = toUri(s3Path);
      Path root;
      if (uri.getPath().isEmpty()) {
        root = new Path("/");
      } else {
        root = new Path(uri.getPath());
      }
      root = getFilesystem().qualify(root);
      compareRoot(root, out);
      out.flush();
      return SUCCESS;
    }

  }

  /**
   * Prune metadata that has not been modified recently.
   */
  static class Prune extends S3GuardTool {
    public static final String NAME = "prune";
    public static final String PURPOSE = "truncate older metadata from " +
        "repository "
        + DATA_IN_S3_IS_PRESERVED;;
    private static final String USAGE = NAME + " [OPTIONS] [s3a://BUCKET]\n" +
        "\t" + PURPOSE + "\n\n" +
        "Common options:\n" +
        "  -" + META_FLAG + " URL - Metadata repository details " +
        "(implementation-specific)\n" +
        "\n" +
        "Amazon DynamoDB-specific options:\n" +
        "  -" + REGION_FLAG + " REGION - Service region for connections\n" +
        "\n" +
        "  URLs for Amazon DynamoDB are of the form dynamodb://TABLE_NAME.\n" +
        "  Specifying both the -" + REGION_FLAG + " option and an S3A path\n" +
        "  is not supported.";

    Prune(Configuration conf) {
      super(conf);

      CommandFormat format = getCommandFormat();
      format.addOptionWithValue(DAYS_FLAG);
      format.addOptionWithValue(HOURS_FLAG);
      format.addOptionWithValue(MINUTES_FLAG);
      format.addOptionWithValue(SECONDS_FLAG);
    }

    @VisibleForTesting
    void setMetadataStore(MetadataStore ms) {
      Preconditions.checkNotNull(ms);
      this.setStore(ms);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    private long getDeltaComponent(TimeUnit unit, String arg) {
      String raw = getCommandFormat().getOptValue(arg);
      if (raw == null || raw.isEmpty()) {
        return 0;
      }
      Long parsed = Long.parseLong(raw);
      return unit.toMillis(parsed);
    }

    public int run(String[] args, PrintStream out) throws
        InterruptedException, IOException {
      List<String> paths = parseArgs(args);
      try {
        parseDynamoDBRegion(paths);
      } catch (ExitUtil.ExitException e) {
        errorln(USAGE);
        throw e;
      }
      initMetadataStore(false);

      Configuration conf = getConf();
      long confDelta = conf.getLong(S3GUARD_CLI_PRUNE_AGE, 0);

      long cliDelta = 0;
      cliDelta += getDeltaComponent(TimeUnit.DAYS, "days");
      cliDelta += getDeltaComponent(TimeUnit.HOURS, "hours");
      cliDelta += getDeltaComponent(TimeUnit.MINUTES, "minutes");
      cliDelta += getDeltaComponent(TimeUnit.SECONDS, "seconds");

      if (confDelta <= 0 && cliDelta <= 0) {
        errorln("You must specify a positive age for metadata to prune.");
      }

      // A delta provided on the CLI overrides if one is configured
      long delta = confDelta;
      if (cliDelta > 0) {
        delta = cliDelta;
      }

      long now = System.currentTimeMillis();
      long divide = now - delta;

      getStore().prune(divide);

      out.flush();
      return SUCCESS;
    }

  }

  /**
   * Get info about a bucket and its S3Guard integration status.
   */
  static class BucketInfo extends S3GuardTool {
    public static final String NAME = "bucket-info";
    public static final String GUARDED_FLAG = "guarded";
    public static final String UNGUARDED_FLAG = "unguarded";
    public static final String AUTH_FLAG = "auth";
    public static final String NONAUTH_FLAG = "nonauth";
    public static final String ENCRYPTION_FLAG = "encryption";
    public static final String MAGIC_FLAG = "magic";
    public static final String MARKERS_FLAG = "markers";
    public static final String MARKERS_AWARE = "aware";

    public static final String PURPOSE = "provide/check S3Guard information"
        + " about a specific bucket";
    private static final String USAGE = NAME + " [OPTIONS] s3a://BUCKET\n"
        + "\t" + PURPOSE + "\n\n"
        + "Common options:\n"
        + "  -" + GUARDED_FLAG + " - Require S3Guard\n"
        + "  -" + ENCRYPTION_FLAG
        + " (none, sse-s3, sse-kms) - Require encryption policy\n"
        + "  -" + MARKERS_FLAG
        + " (aware, keep, delete, authoritative) - directory markers policy\n";

    @VisibleForTesting
    public static final String IS_MARKER_AWARE =
        "The S3A connector can read data in S3 buckets where"
            + " directory markers%n"
            + "are not deleted (optional with later hadoop releases),%n"
            + "and with buckets where they are.%n";

    public BucketInfo(Configuration conf) {
      super(conf, GUARDED_FLAG, UNGUARDED_FLAG, AUTH_FLAG, NONAUTH_FLAG);
      CommandFormat format = getCommandFormat();
      format.addOptionWithValue(ENCRYPTION_FLAG);
      format.addOptionWithValue(MARKERS_FLAG);
    }

    @Override
    String getName() {
      return NAME;
    }

    @Override
    public String getUsage() {
      return USAGE;
    }

    public int run(String[] args, PrintStream out)
        throws InterruptedException, IOException {
      List<String> paths = parseArgs(args);
      if (paths.isEmpty()) {
        errorln(getUsage());
        throw invalidArgs("No bucket specified");
      }
      String s3Path = paths.get(0);
      S3AFileSystem fs = (S3AFileSystem) FileSystem.newInstance(
          toUri(s3Path), getConf());
      setFilesystem(fs);
      Configuration conf = fs.getConf();
      URI fsUri = fs.getUri();
      MetadataStore store = fs.getMetadataStore();
      println(out, "Filesystem %s", fsUri);
      println(out, "Location: %s", fs.getBucketLocation());
      boolean usingS3Guard = !(store instanceof NullMetadataStore);
      boolean authMode = false;
      if (usingS3Guard) {
        out.printf("Filesystem %s is using S3Guard with store %s%n",
            fsUri, store.toString());
        printOption(out, "Authoritative S3Guard",
            METADATASTORE_AUTHORITATIVE, "false");
        authMode = conf.getBoolean(METADATASTORE_AUTHORITATIVE, false);
        printStoreDiagnostics(out, store);
      } else {
        println(out, "Filesystem %s is not using S3Guard", fsUri);
      }
      println(out, "%nS3A Client");

      String endpoint = conf.getTrimmed(ENDPOINT, "");
      println(out, "\tEndpoint: %s=%s",
          ENDPOINT,
          StringUtils.isNotEmpty(endpoint) ? endpoint : "(unset)");
      String encryption =
          printOption(out, "\tEncryption", SERVER_SIDE_ENCRYPTION_ALGORITHM,
              "none");
      printOption(out, "\tInput seek policy", INPUT_FADVISE, INPUT_FADV_NORMAL);

      CommandFormat commands = getCommandFormat();
      if (usingS3Guard) {
        if (commands.getOpt(UNGUARDED_FLAG)) {
          throw badState("S3Guard is enabled for %s", fsUri);
        }
        if (commands.getOpt(AUTH_FLAG) && !authMode) {
          throw badState("S3Guard is enabled for %s,"
              + " but not in authoritative mode", fsUri);
        }
        if (commands.getOpt(NONAUTH_FLAG) && authMode) {
          throw badState("S3Guard is enabled in authoritative mode for %s",
              fsUri);
        }
      } else {
        if (commands.getOpt(GUARDED_FLAG)) {
          throw badState("S3Guard is not enabled for %s", fsUri);
        }
      }

      String desiredEncryption = getCommandFormat()
          .getOptValue(ENCRYPTION_FLAG);
      if (StringUtils.isNotEmpty(desiredEncryption)
          && !desiredEncryption.equalsIgnoreCase(encryption)) {
        throw badState("Bucket %s: required encryption is %s"
                    + " but actual encryption is %s",
                fsUri, desiredEncryption, encryption);
      }

      // directory markers
      processMarkerOption(out, fs,
          getCommandFormat().getOptValue(MARKERS_FLAG));

      // and finally flush the output and report a success.
      out.flush();
      return SUCCESS;
    }

    /**
     * Validate the marker options.
     * @param out output stream
     * @param fs filesystem
     * @param path test path
     * @param marker desired marker option -may be null.
     */
    private void processMarkerOption(final PrintStream out,
        final S3AFileSystem fs,
        final String marker) {
      DirectoryPolicy markerPolicy = fs.getDirectoryMarkerPolicy();
      String desc = markerPolicy.describe();
      println(out, "%nThe directory marker policy is \"%s\"%n", desc);

      DirectoryPolicy.MarkerPolicy mp = markerPolicy.getMarkerPolicy();

      String desiredMarker = marker == null
          ? ""
          : marker.trim();
      final String optionName = mp.getOptionName();
      if (!desiredMarker.isEmpty()) {
        if (MARKERS_AWARE.equalsIgnoreCase(desiredMarker)) {
          // simple awareness test -provides a way to validate compatibility
          // on the command line
          println(out, IS_MARKER_AWARE);
          println(out, "Available Policies: delete");

        } else {
          // compare with current policy
          if (!optionName.equalsIgnoreCase(desiredMarker)) {
            throw badState("Bucket %s: required marker policy is \"%s\""
                    + " but actual policy is \"%s\"",
                fs.getUri(), desiredMarker, optionName);
          }
        }
      }
    }

    private String printOption(PrintStream out,
        String description, String key, String defVal) {
      String t = getFilesystem().getConf().getTrimmed(key, defVal);
      println(out, "%s: %s=%s", description, key, t);
      return t;
    }

  }

  private static S3GuardTool command;

  /**
   * Convert a path to a URI, catching any {@code URISyntaxException}
   * and converting to an invalid args exception.
   * @param s3Path path to convert to a URI
   * @return a URI of the path
   * @throws ExitUtil.ExitException INVALID_ARGUMENT if the URI is invalid
   */
  protected static URI toUri(String s3Path) {
    URI uri;
    try {
      uri = new URI(s3Path);
    } catch (URISyntaxException e) {
      throw invalidArgs("Not a valid fileystem path: %s", s3Path);
    }
    return uri;
  }

  private static void printHelp() {
    if (command == null) {
      errorln("Usage: hadoop " + USAGE);
      errorln("\tperform S3Guard metadata store " +
          "administrative commands.");
    } else {
      errorln("Usage: hadoop " + command.getUsage());
    }
    errorln();
    errorln(COMMON_USAGE);
  }

  private static void errorln() {
    System.err.println();
  }

  private static void errorln(String x) {
    System.err.println(x);
  }

  /**
   * Print a formatted string followed by a newline to the output stream.
   * @param out destination
   * @param format format string
   * @param args optional arguments
   */
  private static void println(PrintStream out, String format, Object... args) {
    out.println(String.format(format, args));
  }

  /**
   * Retrieve and Print store diagnostics.
   * @param out output stream
   * @param store store
   * @throws IOException Failure to retrieve the data.
   */
  protected static void printStoreDiagnostics(PrintStream out,
      MetadataStore store)
      throws IOException {
    Map<String, String> diagnostics = store.getDiagnostics();
    out.println("Metadata Store Diagnostics:");
    for (Map.Entry<String, String> entry : diagnostics.entrySet()) {
      println(out, "\t%s=%s", entry.getKey(), entry.getValue());
    }
  }


  /**
   * Handle store not found by converting to an exit exception
   * with specific error code.
   * @param e exception
   * @return a new exception to throw
   */
  protected static ExitUtil.ExitException storeNotFound(
      FileNotFoundException e) {
    return new ExitUtil.ExitException(
        E_NOT_FOUND, e.toString(), e);
  }

  /**
   * Build the exception to raise on invalid arguments.
   * @param format string format
   * @param args optional arguments for the string
   * @return a new exception to throw
   */
  protected static ExitUtil.ExitException invalidArgs(
      String format, Object...args) {
    return new ExitUtil.ExitException(INVALID_ARGUMENT,
        String.format(format, args));
  }

  /**
   * Build the exception to raise on a bad store/bucket state.
   * @param format string format
   * @param args optional arguments for the string
   * @return a new exception to throw
   */
  protected static ExitUtil.ExitException badState(
      String format, Object...args) {
    return new ExitUtil.ExitException(E_BAD_STATE,
        String.format(format, args));
  }

  /**
   * Execute the command with the given arguments.
   *
   * @param conf Hadoop configuration.
   * @param args command specific arguments.
   * @return exit code.
   * @throws Exception on I/O errors.
   */
  public static int run(Configuration conf, String...args) throws
      Exception {
    /* ToolRunner.run does this too, but we must do it before looking at
    subCommand or instantiating the cmd object below */
    String[] otherArgs = new GenericOptionsParser(conf, args)
        .getRemainingArgs();
    if (otherArgs.length == 0) {
      printHelp();
      throw new ExitUtil.ExitException(E_USAGE, "No arguments provided");
    }
    final String subCommand = otherArgs[0];
    LOG.debug("Executing command {}", subCommand);
    switch (subCommand) {
    case Init.NAME:
      command = new Init(conf);
      break;
    case Destroy.NAME:
      command = new Destroy(conf);
      break;
    case Import.NAME:
      command = new Import(conf);
      break;
    case BucketInfo.NAME:
      command = new BucketInfo(conf);
      break;
    case Diff.NAME:
      command = new Diff(conf);
      break;
    case Prune.NAME:
      command = new Prune(conf);
      break;
    case SetCapacity.NAME:
      command = new SetCapacity(conf);
      break;
    default:
      printHelp();
      throw new ExitUtil.ExitException(E_USAGE,
          "Unknown command " + subCommand);
    }
    return ToolRunner.run(conf, command, otherArgs);
  }

  /**
   * Main entry point. Calls {@code System.exit()} on all execution paths.
   * @param args argument list
   */
  public static void main(String[] args) {
    try {
      int ret = run(new Configuration(), args);
      exit(ret, "");
    } catch (CommandFormat.UnknownOptionException e) {
      errorln(e.getMessage());
      printHelp();
      exit(E_USAGE, e.getMessage());
    } catch (ExitUtil.ExitException e) {
      // explicitly raised exit code
      exit(e.getExitCode(), e.toString());
    } catch (Throwable e) {
      e.printStackTrace(System.err);
      exit(ERROR, e.toString());
    }
  }

  protected static void exit(int status, String text) {
    ExitUtil.terminate(status, text);
  }
}
