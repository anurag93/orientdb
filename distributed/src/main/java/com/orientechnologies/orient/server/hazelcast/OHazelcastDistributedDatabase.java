/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.server.hazelcast;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import com.hazelcast.core.IMap;
import com.hazelcast.core.IQueue;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.OScenarioThreadLocal;
import com.orientechnologies.orient.core.db.OScenarioThreadLocal.RUN_MODE;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.server.config.OServerUserConfiguration;
import com.orientechnologies.orient.server.distributed.ODistributedAbstractPlugin;
import com.orientechnologies.orient.server.distributed.ODistributedConfiguration;
import com.orientechnologies.orient.server.distributed.ODistributedDatabase;
import com.orientechnologies.orient.server.distributed.ODistributedException;
import com.orientechnologies.orient.server.distributed.ODistributedPartition;
import com.orientechnologies.orient.server.distributed.ODistributedPartitioningStrategy;
import com.orientechnologies.orient.server.distributed.ODistributedRequest;
import com.orientechnologies.orient.server.distributed.ODistributedRequest.EXECUTION_MODE;
import com.orientechnologies.orient.server.distributed.ODistributedResponse;
import com.orientechnologies.orient.server.distributed.ODistributedResponseManager;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.task.OAbstractRemoteTask.QUORUM_TYPE;

/**
 * Hazelcast implementation of distributed peer. There is one instance per database. Each node creates own instance to talk with
 * each others.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class OHazelcastDistributedDatabase implements ODistributedDatabase {

  protected final OHazelcastPlugin                    manager;
  protected final OHazelcastDistributedMessageService msgService;

  protected final String                              databaseName;
  protected final static Map<String, IQueue<?>>       queues                     = new HashMap<String, IQueue<?>>();
  protected final Lock                                requestLock;

  protected ODatabaseDocumentTx                       database;

  public static final String                          NODE_QUEUE_PREFIX          = "orientdb.node.";
  public static final String                          NODE_QUEUE_REQUEST_POSTFIX = ".request";
  public static final String                          NODE_QUEUE_UNDO_POSTFIX    = ".undo";

  private static final String                         NODE_LOCK_PREFIX           = "orientdb.reqlock.";

  public OHazelcastDistributedDatabase(final OHazelcastPlugin manager, final OHazelcastDistributedMessageService msgService,
      final String iDatabaseName) {
    this.manager = manager;
    this.msgService = msgService;
    this.databaseName = iDatabaseName;

    this.requestLock = manager.getHazelcastInstance().getLock(NODE_LOCK_PREFIX + iDatabaseName);
  }

  @Override
  public void send2Node(final ODistributedRequest iRequest, final String iTargetNode) {
    final IQueue<ODistributedRequest> queue = msgService.getQueue(OHazelcastDistributedMessageService.getRequestQueueName(
        iTargetNode, iRequest.getDatabaseName()));

    try {
      queue.offer(iRequest, OGlobalConfiguration.DISTRIBUTED_QUEUE_TIMEOUT.getValueAsLong(), TimeUnit.MILLISECONDS);

      Orient.instance().getProfiler()
          .updateCounter("distributed.replication.fixMsgSent", "Number of replication fix messages sent from current node", +1);

    } catch (Throwable e) {
      throw new ODistributedException("Error on sending distributed request against " + iTargetNode, e);
    }

  }

  @Override
  public ODistributedResponse send(final ODistributedRequest iRequest) {
    // TODO: remove threadId
    final Thread currentThread = Thread.currentThread();
    final long threadId = currentThread.getId();

    final String databaseName = iRequest.getDatabaseName();

    final String clusterName = iRequest.getClusterName();

    final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);

    final ODistributedPartitioningStrategy strategy = manager.getPartitioningStrategy(cfg.getPartitionStrategy(clusterName));
    final ODistributedPartition partition = strategy.getPartition(manager, databaseName, clusterName);
    final Set<String> nodes = partition.getNodes();

    final IQueue<ODistributedRequest>[] reqQueues = getRequestQueues(databaseName, nodes);

    final int queueSize = nodes.size();
    int quorum = calculateQuorum(iRequest, clusterName, cfg, nodes, queueSize);

    iRequest.setSenderNodeName(manager.getLocalNodeName());
    iRequest.setSenderThreadId(threadId);

    int availableNodes = 0;
    for (String node : nodes) {
      if (manager.isNodeAvailable(node))
        availableNodes++;
      else {
        if (ODistributedServerLog.isDebugEnabled())
          ODistributedServerLog.debug(this, getLocalNodeNameAndThread(), node, DIRECTION.OUT,
              "skip listening of response because node '%s' is not online", node);
      }
    }

    int expectedSynchronousResponses = quorum > 0 ? Math.min(quorum, availableNodes) : queueSize;
    final boolean waitLocalNode = nodes.contains(manager.getLocalNodeName()) && cfg.isReadYourWrites(clusterName);

    // CREATE THE RESPONSE MANAGER
    final ODistributedResponseManager currentResponseMgr = new ODistributedResponseManager(manager, iRequest, nodes,
        expectedSynchronousResponses, quorum, waitLocalNode, iRequest.getPayload().getSynchronousTimeout(
            expectedSynchronousResponses), iRequest.getPayload().getTotalTimeout(queueSize));

    msgService.registerRequest(iRequest.getId(), currentResponseMgr);

    if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, getLocalNodeNameAndThread(), nodes.toString(), DIRECTION.OUT, "request %s",
          iRequest.getPayload());

    final long timeout = OGlobalConfiguration.DISTRIBUTED_QUEUE_TIMEOUT.getValueAsLong();

    try {
      requestLock.lock();
      try {
        // LOCK = ASSURE MESSAGES IN THE QUEUE ARE INSERTED SEQUENTIALLY AT CLUSTER LEVEL
        // BROADCAST THE REQUEST TO ALL THE NODE QUEUES
        for (IQueue<ODistributedRequest> queue : reqQueues) {
          queue.offer(iRequest, timeout, TimeUnit.MILLISECONDS);
        }

      } finally {
        requestLock.unlock();
      }

      Orient.instance().getProfiler()
          .updateCounter("distributed.replication.msgSent", "Number of replication messages sent from current node", +1);

      return collectResponses(iRequest, currentResponseMgr);

    } catch (Throwable e) {
      throw new ODistributedException("Error on sending distributed request against " + databaseName
          + (clusterName != null ? ":" + clusterName : ""), e);
    }

  }

  protected int calculateQuorum(final ODistributedRequest iRequest, final String clusterName, final ODistributedConfiguration cfg,
      final Set<String> nodes, final int queueSize) {
    final QUORUM_TYPE quorumType = iRequest.getPayload().getQuorumType();

    int quorum = 0;

    switch (quorumType) {
    case NONE:
      quorum = 0;
      break;
    case READ:
      quorum = cfg.getReadQuorum(clusterName);
      break;
    case WRITE:
      quorum = cfg.getWriteQuorum(clusterName);
      break;
    }

    final boolean failureAvailableNodesLessQuorum = cfg.getFailureAvailableNodesLessQuorum(clusterName);

    if (quorum > queueSize)
      if (failureAvailableNodesLessQuorum)
        throw new ODistributedException(
            "Quorum cannot be reached because it is major than available nodes and 'failureAvailableNodesLessQuorum' settings is true");
      else {
        // SET THE QUORUM TO THE AVAILABLE NODE SIZE
        ODistributedServerLog.debug(this, getLocalNodeNameAndThread(), nodes.toString(), DIRECTION.OUT,
            "quorum less then available nodes, downgrade quorum to %d", queueSize);
        quorum = queueSize;
      }
    return quorum;
  }

  protected ODistributedResponse collectResponses(final ODistributedRequest iRequest,
      final ODistributedResponseManager currentResponseMgr) throws InterruptedException {
    if (iRequest.getExecutionMode() == EXECUTION_MODE.NO_RESPONSE)
      return null;

    final long beginTime = System.currentTimeMillis();

    // WAIT FOR THE MINIMUM SYNCHRONOUS RESPONSES (WRITE QUORUM)
    if (!currentResponseMgr.waitForSynchronousResponses()) {
      ODistributedServerLog.warn(this, getLocalNodeNameAndThread(), null, DIRECTION.IN,
          "timeout (%dms) on waiting for synchronous responses from nodes=%s responsesSoFar=%s request=%s",
          System.currentTimeMillis() - beginTime, currentResponseMgr.getExpectedNodes(), currentResponseMgr.getRespondingNodes(),
          iRequest);
    }

    if (currentResponseMgr.isWaitForLocalNode() && !currentResponseMgr.isReceivedCurrentNode())
      ODistributedServerLog.warn(this, getLocalNodeNameAndThread(), manager.getLocalNodeName(), DIRECTION.IN,
          "no response received from local node about request %s", iRequest);

    // QUORUM REACHED
    return currentResponseMgr.getResponse(iRequest.getPayload().getResultStrategy());
  }

  public void configureDatabase(final ODatabaseDocumentTx iDatabase) {
    // TODO: USE THE POOL!
    if (iDatabase == null) {
      // OPEN IT
      final OServerUserConfiguration replicatorUser = manager.getServerInstance().getUser(
          ODistributedAbstractPlugin.REPLICATOR_USER);
      database = (ODatabaseDocumentTx) manager.getServerInstance().openDatabase("document", databaseName, replicatorUser.name,
          replicatorUser.password);
    } else
      database = iDatabase;

    // CREATE A QUEUE PER DATABASE
    final String queueName = OHazelcastDistributedMessageService.getRequestQueueName(manager.getLocalNodeName(), databaseName);
    final IQueue<ODistributedRequest> requestQueue = msgService.getQueue(queueName);

    if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, getLocalNodeNameAndThread(), null, DIRECTION.NONE,
          "listening for incoming requests on queue: %s", queueName);

    // UNDO PREVIOUS MESSAGE IF ANY
    final IMap<Object, Object> undoMap = restoreMessagesBeforeFailure();

    msgService.checkForPendingMessages(requestQueue, queueName);

    // CREATE THREAD LISTENER AGAINST orientdb.node.<node>.<db>.request, ONE PER NODE, THEN DISPATCH THE MESSAGE INTERNALLY USING
    // THE THREAD ID
    new Thread(new Runnable() {
      @Override
      public void run() {
        while (!Thread.interrupted()) {
          String senderNode = null;
          ODistributedRequest message = null;
          try {
            // TODO: ASSURE WE DON'T LOOSE THE MSG AT THIS POINT. HAZELCAST TX? OR PEEK? ARE NOT BLOCKING :-(
            message = requestQueue.take();

            // SAVE THE MESSAGE IN THE UNDO MAP IN CASE OF FAILURE
            undoMap.put(databaseName, message);

            if (message != null) {
              senderNode = message.getSenderNodeName();
              onMessage(message);
            }

            // OK: REMOVE THE UNDO BUFFER
            undoMap.remove(databaseName);

          } catch (InterruptedException e) {
            // EXIT CURRENT THREAD
            Thread.interrupted();
            break;

          } catch (Throwable e) {
            ODistributedServerLog.error(this, getLocalNodeNameAndThread(), senderNode, DIRECTION.IN,
                "error on reading distributed request: %s", e, message != null ? message.getPayload() : "-");
          }
        }
      }
    }).start();

    checkLocalNodeInConfiguration();
  }

  /**
   * Execute the remote call on the local node and send back the result
   */
  protected void onMessage(final ODistributedRequest iRequest) {
    OScenarioThreadLocal.INSTANCE.set(RUN_MODE.RUNNING_DISTRIBUTED);

    try {
      if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, manager.getLocalNodeName(),
            iRequest.getSenderNodeName() + ":" + iRequest.getSenderThreadId(), DIRECTION.IN, "request %s", iRequest.getPayload());

      // EXECUTE IT LOCALLY
      final Serializable responsePayload;
      try {
        ODatabaseRecordThreadLocal.INSTANCE.set(database);
        iRequest.getPayload().setNodeSource(iRequest.getSenderNodeName());
        responsePayload = manager.executeOnLocalNode(iRequest, database);
      } finally {
        database.getLevel1Cache().clear();
      }

      if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, manager.getLocalNodeName(),
            iRequest.getSenderNodeName() + ":" + iRequest.getSenderThreadId(), DIRECTION.OUT,
            "sending back response %s to request %s", responsePayload, iRequest.getPayload());

      final OHazelcastDistributedResponse response = new OHazelcastDistributedResponse(iRequest.getId(),
          manager.getLocalNodeName(), iRequest.getSenderNodeName(), iRequest.getSenderThreadId(), responsePayload);

      try {
        // GET THE SENDER'S RESPONSE QUEUE
        final IQueue<ODistributedResponse> queue = msgService.getQueue(OHazelcastDistributedMessageService
            .getResponseQueueName(iRequest.getSenderNodeName()));

        if (!queue.offer(response, OGlobalConfiguration.DISTRIBUTED_QUEUE_TIMEOUT.getValueAsLong(), TimeUnit.MILLISECONDS))
          throw new ODistributedException("Timeout on dispatching response to the thread queue " + iRequest.getSenderNodeName()
              + ":" + iRequest.getSenderThreadId());

      } catch (Exception e) {
        throw new ODistributedException("Cannot dispatch response to the thread queue " + iRequest.getSenderNodeName() + ":"
            + iRequest.getSenderThreadId(), e);
      }

    } finally {
      OScenarioThreadLocal.INSTANCE.set(RUN_MODE.DEFAULT);
    }
  }

  @SuppressWarnings("unchecked")
  protected IQueue<ODistributedRequest>[] getRequestQueues(final String iDatabaseName, final Set<String> nodes) {
    final IQueue<ODistributedRequest>[] queues = new IQueue[nodes.size()];

    int i = 0;
    for (String node : nodes)
      queues[i++] = msgService.getQueue(OHazelcastDistributedMessageService.getRequestQueueName(node, iDatabaseName));

    return queues;
  }

  public void shutdown() {
    try {
      database.close();
    } catch (Exception e) {
    }
  }

  /**
   * Composes the undo queue name based on node name.
   */
  protected String getUndoMapName(final String iDatabaseName) {
    final StringBuilder buffer = new StringBuilder();
    buffer.append(NODE_QUEUE_PREFIX);
    buffer.append(manager.getLocalNodeName());
    if (iDatabaseName != null) {
      buffer.append('.');
      buffer.append(iDatabaseName);
    }
    buffer.append(NODE_QUEUE_UNDO_POSTFIX);
    return buffer.toString();
  }

  protected String getLocalNodeNameAndThread() {
    return manager.getLocalNodeName() + ":" + Thread.currentThread().getId();
  }

  protected IMap<Object, Object> restoreMessagesBeforeFailure() {
    final IMap<Object, Object> undoMap = manager.getHazelcastInstance().getMap(getUndoMapName(databaseName));
    final ODistributedRequest undoRequest = (ODistributedRequest) undoMap.remove(databaseName);
    if (undoRequest != null) {
      ODistributedServerLog.warn(this, getLocalNodeNameAndThread(), null, DIRECTION.NONE,
          "restore last replication message before the crash for database %s: %s", databaseName, undoRequest);

      try {
        onMessage(undoRequest);
      } catch (Throwable t) {
        ODistributedServerLog.error(this, getLocalNodeNameAndThread(), null, DIRECTION.NONE,
            "error on executing restored message for database %s", t, databaseName);
      }

    }
    return undoMap;
  }

  public ODatabaseDocumentTx getDatabase() {
    return database;
  }

  protected void checkLocalNodeInConfiguration() {
    final String localNode = manager.getLocalNodeName();

    // LOAD DATABASE FILE IF ANY
    manager.loadDatabaseConfiguration(databaseName, manager.getDistributedConfigFile(databaseName));

    final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);
    for (String clusterName : cfg.getClusterNames()) {
      final List<List<String>> partitions = cfg.getPartitions(clusterName);
      if (partitions != null)
        for (List<String> partition : partitions) {
          for (String node : partition)
            if (node.equals(localNode))
              // FOUND: DO NOTHING
              return;
        }
    }

    // NOT FOUND: ADD THE NODE IN CONFIGURATION. LOOK FOR $newNode TAG
    boolean dirty = false;
    for (String clusterName : cfg.getClusterNames()) {
      final List<List<String>> partitions = cfg.getPartitions(clusterName);
      if (partitions != null)
        for (int p = 0; p < partitions.size(); ++p) {
          List<String> partition = partitions.get(p);
          for (String node : partition)
            if (node.equalsIgnoreCase(ODistributedConfiguration.NEW_NODE_TAG)) {
              ODistributedServerLog.info(this, manager.getLocalNodeName(), null, DIRECTION.NONE,
                  "adding node '%s' in partition: %s.%s.%d", localNode, databaseName, clusterName, p);
              partition.add(localNode);
              dirty = true;
              break;
            }
        }
    }

    if (dirty) {
      final ODocument doc = cfg.serialize();
      manager.getConfigurationMap().put(OHazelcastPlugin.CONFIG_DATABASE_PREFIX + databaseName, doc);
      manager.updateDatabaseConfiguration(databaseName, doc);
    }
  }

}
