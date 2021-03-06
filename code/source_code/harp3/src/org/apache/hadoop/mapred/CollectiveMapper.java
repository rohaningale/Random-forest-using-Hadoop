/*
 * Copyright 2013-2016 Indiana University
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

package org.apache.hadoop.mapred;

import it.unimi.dsi.fastutil.ints.Int2IntMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Mapper;

import edu.iu.harp.client.Event;
import edu.iu.harp.client.EventType;
import edu.iu.harp.client.SyncClient;
import edu.iu.harp.collective.AllgatherCollective;
import edu.iu.harp.collective.AllreduceCollective;
import edu.iu.harp.collective.BcastCollective;
import edu.iu.harp.collective.Communication;
import edu.iu.harp.collective.LocalGlobalSyncCollective;
import edu.iu.harp.collective.ReduceCollective;
import edu.iu.harp.collective.RegroupCollective;
import edu.iu.harp.io.ConnPool;
import edu.iu.harp.io.Constant;
import edu.iu.harp.io.DataMap;
import edu.iu.harp.io.EventQueue;
import edu.iu.harp.partition.Partitioner;
import edu.iu.harp.partition.Table;
import edu.iu.harp.resource.ResourcePool;
import edu.iu.harp.resource.Simple;
import edu.iu.harp.server.Server;
import edu.iu.harp.worker.Workers;

/**
 * CollectiveMapper is extended from original
 * mapper in Hadoop. It includes new APIs for
 * in-memory collective communication.
 * 
 * @author zhangbj
 * 
 * @param <KEYIN>
 *          Input key
 * @param <VALUEIN>
 *          Input value
 * @param <KEYOUT>
 *          Output key
 * @param <VALUEOUT>
 *          Output value
 */
public class CollectiveMapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT>
  extends
  Mapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {

  protected static final Log LOG = LogFactory
    .getLog(CollectiveMapper.class);

  private int workerID;
  private Workers workers;
  private EventQueue eventQueue;
  private DataMap dataMap;
  private Server server;
  private SyncClient client;

  /**
   * A Key-Value reader to read key-value inputs
   * for this worker.
   *
   * @author zhangbj
   */
  protected class KeyValReader {
    private Context context;

    protected KeyValReader(Context context) {
      this.context = context;
    }

    public boolean nextKeyValue()
      throws IOException, InterruptedException {
      return this.context.nextKeyValue();
    }

    public KEYIN getCurrentKey()
      throws IOException, InterruptedException {
      return this.context.getCurrentKey();
    }

    public VALUEIN getCurrentValue()
      throws IOException, InterruptedException {
      return this.context.getCurrentValue();
    }
  }

  /**
   * If lock file couldn't be gotten, quit.
   * 
   * @param lockFile
   * @param fs
   * @return
   */
  private boolean tryLockFile(String lockFile,
    FileSystem fs) {
    LOG.info("TRY LOCK FILE " + lockFile + " "
      + fs.getHomeDirectory());
    Path path =
      new Path(fs.getHomeDirectory(), lockFile);
    boolean retry = false;
    int retryCount = 0;
    do {
      try {
        retry = !fs.exists(path);
      } catch (Exception e) {
        LOG.error("Read lock file exception.", e);
        retry = true;
      }
      if (retry) {
        try {
          Thread.sleep(Constant.SHORT_SLEEP);
        } catch (InterruptedException e) {
        }
        retryCount++;
        LOG.info("Fail to read nodes lock file "
          + path.toString()
          + ", retry... Retry count: "
          + retryCount);
        if (retryCount == Constant.LARGE_RETRY_COUNT) {
          return false;
        }
      }
    } while (retry);
    return true;
  }

  private Map<Integer, Integer> getTaskWorkerMap(
    String tasksFile, FileSystem fs) {
    LOG.info("Get task file " + tasksFile);
    Map<Integer, Integer> taskWorkerMap = null;
    Path path =
      new Path(fs.getHomeDirectory(), tasksFile);
    try {
      taskWorkerMap =
        new TreeMap<Integer, Integer>();
      FSDataInputStream in = fs.open(path);
      BufferedReader br =
        new BufferedReader(new InputStreamReader(
          in));
      String line = null;
      String[] tokens = null;
      while ((line = br.readLine()) != null) {
        tokens = line.split("\t");
        taskWorkerMap.put(
          Integer.parseInt(tokens[0]),
          Integer.parseInt(tokens[1]));
      }
      br.close();
    } catch (IOException e) {
      LOG.error("No TASK FILE FOUND");
      taskWorkerMap = null;
    }
    return taskWorkerMap;
  }

  private BufferedReader getNodesReader(
    String nodesFile, FileSystem fs)
    throws IOException {
    LOG.info("Get nodes file " + nodesFile);
    Path path =
      new Path(fs.getHomeDirectory(), nodesFile);
    FSDataInputStream in = fs.open(path);
    BufferedReader br =
      new BufferedReader(
        new InputStreamReader(in));
    return br;
  }

  private boolean initCollCommComponents(
    Context context) throws IOException {
    // Get file names
    String jobDir = context.getJobID().toString();
    String nodesFile = jobDir + "/nodes";
    String tasksFile = jobDir + "/tasks";
    String lockFile = jobDir + "/lock";
    FileSystem fs =
      FileSystem.get(context.getConfiguration());
    // Try lock
    boolean isSuccess = tryLockFile(lockFile, fs);
    if (!isSuccess) {
      return false;
    }
    Map<Integer, Integer> taskWorkerMap =
      getTaskWorkerMap(tasksFile, fs);
    // Get worker ID
    int taskID =
      context.getTaskAttemptID().getTaskID()
        .getId();
    LOG.info("Task ID " + taskID);
    if (taskWorkerMap == null) {
      workerID = taskID;
    } else {
      workerID = taskWorkerMap.get(taskID);
    }
    LOG.info("WORKER ID: " + workerID);
    // Get nodes file and initialize workers
    BufferedReader br =
      getNodesReader(nodesFile, fs);
    try {
      workers = new Workers(br, workerID);
      br.close();
    } catch (Exception e) {
      LOG.error("Cannot initialize workers.", e);
      throw new IOException(e);
    }
    eventQueue = new EventQueue();
    dataMap = new DataMap();
    client = new SyncClient(workers);
    // Initialize receiver
    String host = workers.getSelfInfo().getNode();
    int port = workers.getSelfInfo().getPort();
    try {
      server =
        new Server(host, port, eventQueue,
          dataMap, workers);
    } catch (Exception e) {
      LOG
        .error("Cannot initialize receivers.", e);
      throw new IOException(e);
    }
    client.start();
    server.start();
    context.getProgress();
    isSuccess =
      barrier("start-worker", "handshake");
    LOG.info("Barrier: " + isSuccess);
    return isSuccess;
  }

  /**
   * Get the ID of this worker.
   * 
   * @return Worker ID
   */
  public int getSelfID() {
    return this.workerID;
  }

  /**
   * Get the ID of the master worker.
   * 
   * @return Master ID
   */
  public int getMasterID() {
    return this.workers.getMasterID();
  }

  /**
   * Check if this worker is the master worker.
   * 
   * @return is this the master worker ID?
   */
  public boolean isMaster() {
    return this.workers.isMaster();
  }

  /**
   * Get the total number of workers.
   * 
   * @return the number of workers
   */
  public int getNumWorkers() {
    return this.workers.getNumWorkers();
  }

  /**
   * Get the minimum worker ID.
   * 
   * @return worker ID
   */
  public int getMinID() {
    return this.workers.getMinID();
  }

  /**
   * Get the maximum worker ID
   * 
   * @return
   */
  public int getMaxID() {
    return this.workers.getMaxID();
  }

  /**
   * Synchronize workers through a barrier
   * 
   * @param contextName
   * @param operationName
   * @return a boolean tells if the operation
   *         succeeds
   */
  public boolean barrier(String contextName,
    String operationName) {
    boolean isSuccess =
      Communication.barrier(contextName,
        operationName, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }

  /**
   * Broadcast the partitions of the table on a worker to other workers.
   *
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param table
   *          the data structure to broadcast/receive data
   * @param bcastWorkerID
   *          the worker to broadcast data
   * @param useMSTBcast
   *          default broadcast algorithm is pipelining, set this option to true to enable minimum spanning tree algorithm
   * @return boolean 
   *         true if the operationsucceeds
   */
  public <P extends Simple> boolean broadcast(
    String contextName, String operationName,
    Table<P> table, int bcastWorkerID,
    boolean useMSTBcast) {
    boolean isSucess =
      BcastCollective.broadcast(contextName,
        operationName, table, bcastWorkerID,
        useMSTBcast, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSucess;
  }

  /**
   * Reduce the partitions of the tables to one of them.
   *
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param table
   *          the data structure to broadcast/receive data
   * @param reduceWorkerID
   *          the worker ID to receive the reduced data
   * @return boolean 
   *          true if the operation succeeds
   */
  public <P extends Simple> boolean reduce(
    String contextName, String operationName,
    Table<P> table, int reduceWorkerID) {
    boolean isSuccess =
      ReduceCollective.reduce(contextName,
        operationName, table, reduceWorkerID,
        dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }

  /**
   * Allgather partitions of the tables to all the local tables.
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param table
   *          the data structure to broadcast/receive data
   * @return boolean 
   *          true if the operation succeeds
   */
  public <P extends Simple> boolean allgather(
    String contextName, String operationName,
    Table<P> table) {
    boolean isSuccess =
      AllgatherCollective.allgather(contextName,
        operationName, table, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }

  /**
   * Allreduce partitions of the tables to all the local tables.
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param table
   *          the data structure to broadcast/receive data
   * @return boolean 
   *          true if the operation succeeds
   */
  public <P extends Simple> boolean allreduce(
    String contextName, String operationName,
    Table<P> table) {
    boolean isSuccess =
      AllreduceCollective.allreduce(contextName,
        operationName, table, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }

  /**
   * Regroup the partitions of the tables based on a partitioner.
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param table
   *          the data structure to broadcast/receive data
   * @param partitioner
   *          tells which partition to go to which worker for regrouping, e.g. new Partitioner(numWorkers)
   * @return boolean 
   *          true if the operation succeeds
   */
  public
    <P extends Simple, PT extends Partitioner>
    boolean regroup(String contextName,
      String operationName, Table<P> table,
      PT partitioner) {
    boolean isSucess =
      RegroupCollective.regroupCombine(
        contextName, operationName, table,
        partitioner, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSucess;
  }

  /**
   * Retrieve the partitions from globalTable to localTable based on partition ID matching
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param localTable
   *          contains temporary local partitions
   * @param globalTable
   *          is viewed as a distributed dataset where each partition ID is unique across processes
   * @param useBcast
   *          if broadcasting is used when a partition is required to send to all the processes.
   * @return boolean 
   *          true if the operation succeeds
   */
  public <P extends Simple> boolean pull(
    String contextName, String operationName,
    Table<P> localTable, Table<P> globalTable,
    boolean useBcast) {
    boolean isSuccess =
      LocalGlobalSyncCollective.pull(contextName,
        operationName, localTable, globalTable,
        useBcast, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }

  /**
   * Send the partitions from localTable to globalTable based on the partition ID matching
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param localTable
   *          contains temporary local partitions
   * @param globalTable
   *          is viewed as a distributed dataset where each partition ID is unique across processes
   * @param partitioner
   *           if some local partitions is not shown in the globalTable, a partitioner can be used to decide where partitions with this partition ID go
   * @return boolean 
   *          true if the operation succeeds
   */
  public
    <P extends Simple, PT extends Partitioner>
    boolean push(String contextName,
      String operationName, Table<P> localTable,
      Table<P> globalTable, PT partitioner) {
    boolean isSuccess =
      LocalGlobalSyncCollective.push(contextName,
        operationName, localTable, globalTable,
        partitioner, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }


  /**
   * @param contextName
   *          user defined name to separate operations indifferent groups
   * @param operationName
   *          user defined name to separate operations
   * @param globalTable
   *         a distributed dataset where each partition ID is unique across processes
   * @param rotateMap
   *         the mapping between source worker and target worker
   * @return boolean 
   *          true if the operation succeeds
   */
  public <P extends Simple> boolean rotate(
    String contextName, String operationName,
    Table<P> globalTable, Int2IntMap rotateMap) {
    boolean isSuccess =
      LocalGlobalSyncCollective.rotate(
        contextName, operationName, globalTable,
        rotateMap, dataMap, workers);
    dataMap.cleanOperationData(contextName,
      operationName);
    return isSuccess;
  }

  /**
   * Get an event from the event queue.
   * 
   * @return an event object, null if the queue is
   *         empty
   */
  public Event getEvent() {
    return eventQueue.getEvent();
  }

  /**
   * Wait for an event from the queue
   * 
   * @return the event object
   */
  public Event waitEvent() {
    return eventQueue.waitEvent();
  }

  /**
   * Send an event to the local (local event), to
   * a remote worker (message event), or to the
   * rest workers (collective event).
   * 
   * @param event
   *          an event
   * @return a boolean tells if the operation
   *         succeeds
   */
  public boolean sendEvent(Event event) {
    if (event.getEventType() == EventType.LOCAL_EVENT
      && event.getBody() != null) {
      eventQueue.addEvent(new Event(event
        .getEventType(), event.getContextName(),
        this.workerID, this.workerID, event
          .getBody()));
      return true;
    } else if (event.getEventType() == EventType.MESSAGE_EVENT) {
      return client.submitMessageEvent(event);
    } else if (event.getEventType() == EventType.COLLECTIVE_EVENT) {
      return client.submitCollectiveEvent(event);
    } else {
      return false;
    }
  }

  /**
   * Free the objects cached in the pool.
   * 
   * @return the resource pool
   */
  protected void freeMemory() {
    ResourcePool.get().clean();
  }

  /**
   * Free the connections cached in the pool.
   * 
   * @return the resource pool
   */
  protected void freeConn() {
    ConnPool.get().clean();
  }

  protected void logMemUsage() {
    LOG.info("Total Memory (bytes): " + " "
      + Runtime.getRuntime().totalMemory()
      + ", Free Memory (bytes): "
      + Runtime.getRuntime().freeMemory());
  }

  protected void logGCTime() {
    long totalGarbageCollections = 0;
    long garbageCollectionTime = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory
      .getGarbageCollectorMXBeans()) {
      long count = gc.getCollectionCount();
      if (count >= 0) {
        totalGarbageCollections += count;
      }
      long time = gc.getCollectionTime();
      if (time >= 0) {
        garbageCollectionTime += time;
      }
    }
    LOG.info("Total Garbage Collections: "
      + totalGarbageCollections
      + ", Total Garbage Collection Time (ms): "
      + garbageCollectionTime);
  }

  /**
   * Called once at the beginning of the task.
   */
  protected void setup(Context context)
    throws IOException, InterruptedException {
    // NOTHING
  }

  protected void mapCollective(
    KeyValReader reader, Context context)
    throws IOException, InterruptedException {
    while (reader.nextKeyValue()) {
      // Do...
    }
  }

  /**
   * Called once at the end of the task.
   */
  protected void cleanup(Context context)
    throws IOException, InterruptedException {
    // NOTHING
  }

  /**
   * Expert users can override this method for
   * more complete control over the execution of
   * the Mapper.
   * 
   * @param context
   * @throws IOException
   */
  public void run(Context context)
    throws IOException, InterruptedException {
    // Logger.getLogger("net.openhft").setLevel(Level.OFF);
    long time1 = System.currentTimeMillis();
    boolean success =
      initCollCommComponents(context);
    long time2 = System.currentTimeMillis();
    LOG.info("Initialize Harp components (ms): "
      + (time2 - time1));
    if (!success) {
      if (client != null) {
        client.stop();
      }
      // Stop the server
      if (server != null) {
        server.stop();
      }
      throw new IOException(
        "Fail to do master barrier.");
    }
    setup(context);
    KeyValReader reader =
      new KeyValReader(context);
    try {
      mapCollective(reader, context);
      ResourcePool.get().log();
      ConnPool.get().log();
    } catch (Throwable t) {
      LOG.error("Fail to do map-collective.", t);
      throw new IOException(t);
    } finally {
      cleanup(context);
      ConnPool.get().clean();
      client.stop();
      server.stop();
      ForkJoinPool.commonPool().awaitQuiescence(
        Constant.TERMINATION_TIMEOUT,
        TimeUnit.SECONDS);
    }
  }
}