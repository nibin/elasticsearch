/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.decider.ClusterRebalanceAllocationDecider;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import java.util.ArrayList;
import java.util.Collections;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;

public class AddIncrementallyTests extends ESAllocationTestCase {
    private final Logger logger = Loggers.getLogger(AddIncrementallyTests.class);

    public void testAddNodesAndIndices() {
        Settings.Builder settings = Settings.builder();
        settings.put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), ClusterRebalanceAllocationDecider.ClusterRebalanceType.ALWAYS.toString());
        AllocationService service = createAllocationService(settings.build());

        ClusterState clusterState = initCluster(service, 1, 3, 3, 1);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(9));
        int nodeOffset = 1;
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(0));
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(3));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(2));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        assertAtLeastOneIndexShardPerNode(clusterState);
        clusterState = removeNodes(clusterState, service, 1);
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(2));

        clusterState = addIndex(clusterState, service, 3, 2, 3);
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(2));
        assertNumIndexShardsPerNode(clusterState, "test3", Matchers.equalTo(2));
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));

        clusterState = addIndex(clusterState, service, 4, 2, 3);
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(4));
        assertNumIndexShardsPerNode(clusterState, "test4", Matchers.equalTo(2));
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(0));
        clusterState = removeNodes(clusterState, service, 1);
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(4));
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(0));
        logger.debug("ClusterState: {}", clusterState.getRoutingNodes().prettyPrint());
    }

    public void testMinimalRelocations() {
        Settings.Builder settings = Settings.builder();
        settings.put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), ClusterRebalanceAllocationDecider.ClusterRebalanceType.ALWAYS.toString())
                .put("cluster.routing.allocation.node_concurrent_recoveries", 2);
        AllocationService service = createAllocationService(settings.build());

        ClusterState clusterState = initCluster(service, 1, 3, 3, 1);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(9));
        int nodeOffset = 1;
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(0));
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(3));

        logger.info("now, start one more node, check that rebalancing will happen because we set it to always");
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        nodes.add(newNode("node2"));
        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();

        RoutingAllocation.Result routingResult = service.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        RoutingNodes routingNodes = clusterState.getRoutingNodes();

        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();
        assertTrue(routingResult.changed());
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(STARTED).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(STARTED).size(), Matchers.equalTo(4));
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertTrue(routingResult.changed());

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(STARTED).size(), Matchers.equalTo(6));
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertTrue(routingResult.changed());

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        assertFalse(routingResult.changed());
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(2));
        logger.debug("ClusterState: {}", clusterState.getRoutingNodes().prettyPrint());
    }

    public void testMinimalRelocationsNoLimit() {
        Settings.Builder settings = Settings.builder();
        settings.put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), ClusterRebalanceAllocationDecider.ClusterRebalanceType.ALWAYS.toString())
                .put("cluster.routing.allocation.node_concurrent_recoveries", 100)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 100);
        AllocationService service = createAllocationService(settings.build());

        ClusterState clusterState = initCluster(service, 1, 3, 3, 1);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(9));
        int nodeOffset = 1;
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(STARTED).size(), Matchers.equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), Matchers.equalTo(0));
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(3));

        logger.info("now, start one more node, check that rebalancing will happen because we set it to always");
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        nodes.add(newNode("node2"));
        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();

        RoutingAllocation.Result routingResult = service.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        RoutingNodes routingNodes = clusterState.getRoutingNodes();

        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();
        assertTrue(routingResult.changed());
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(STARTED).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(STARTED).size(), Matchers.equalTo(4));
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertTrue(routingResult.changed());

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(STARTED).size(), Matchers.equalTo(6));
        assertThat(clusterState.getRoutingNodes().node("node2").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(INITIALIZING).size(), Matchers.equalTo(0));
        assertTrue(routingResult.changed());

        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        assertFalse(routingResult.changed());
        assertNumIndexShardsPerNode(clusterState, Matchers.equalTo(2));
        logger.debug("ClusterState: {}", clusterState.getRoutingNodes().prettyPrint());
    }


    private void assertNumIndexShardsPerNode(ClusterState state, Matcher<Integer> matcher) {
        for (ObjectCursor<String> index : state.routingTable().indicesRouting().keys()) {
            assertNumIndexShardsPerNode(state, index.value, matcher);
        }
    }

    private void assertNumIndexShardsPerNode(ClusterState state, String index, Matcher<Integer> matcher) {
        for (RoutingNode node : state.getRoutingNodes()) {
            assertThat(node.shardsWithState(index, STARTED).size(), matcher);
        }
    }


    private void assertAtLeastOneIndexShardPerNode(ClusterState state) {
        for (ObjectCursor<String> index : state.routingTable().indicesRouting().keys()) {

            for (RoutingNode node : state.getRoutingNodes()) {
                assertThat(node.shardsWithState(index.value, STARTED).size(), Matchers.greaterThanOrEqualTo(1));
            }
        }

    }

    private ClusterState addNodes(ClusterState clusterState, AllocationService service, int numNodes, int nodeOffset) {
        logger.info("now, start [{}] more node, check that rebalancing will happen because we set it to always", numNodes);
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        for (int i = 0; i < numNodes; i++) {
            nodes.add(newNode("node" + (i + nodeOffset)));
        }

        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();

        RoutingAllocation.Result routingResult = service.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        // move initializing to started
        return applyStartedShardsUntilNoChange(clusterState, service);
    }

    private ClusterState initCluster(AllocationService service, int numberOfNodes, int numberOfIndices, int numberOfShards,
                                     int numberOfReplicas) {
        MetaData.Builder metaDataBuilder = MetaData.builder();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();

        for (int i = 0; i < numberOfIndices; i++) {
            IndexMetaData.Builder index = IndexMetaData.builder("test" + i).settings(settings(Version.CURRENT)).numberOfShards(numberOfShards).numberOfReplicas(
                    numberOfReplicas);
            metaDataBuilder = metaDataBuilder.put(index);
        }

        MetaData metaData = metaDataBuilder.build();

        for (ObjectCursor<IndexMetaData> cursor : metaData.indices().values()) {
            routingTableBuilder.addAsNew(cursor.value);
        }

        RoutingTable initialRoutingTable = routingTableBuilder.build();

        logger.info("start {} nodes", numberOfNodes);
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder();
        for (int i = 0; i < numberOfNodes; i++) {
            nodes.add(newNode("node" + i));
        }
        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).nodes(nodes).metaData(metaData).routingTable(initialRoutingTable).build();
        RoutingAllocation.Result routingResult = service.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("restart all the primary shards, replicas will start initializing");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();
        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("start the replica shards");
        routingNodes = clusterState.getRoutingNodes();
        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();
        routingNodes = clusterState.getRoutingNodes();

        logger.info("complete rebalancing");
        return applyStartedShardsUntilNoChange(clusterState, service);
    }

    private ClusterState addIndex(ClusterState clusterState, AllocationService service, int indexOrdinal, int numberOfShards,
                                  int numberOfReplicas) {
        MetaData.Builder metaDataBuilder = MetaData.builder(clusterState.getMetaData());
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder(clusterState.routingTable());

        IndexMetaData.Builder index = IndexMetaData.builder("test" + indexOrdinal).settings(settings(Version.CURRENT)).numberOfShards(numberOfShards).numberOfReplicas(
                numberOfReplicas);
        IndexMetaData imd = index.build();
        metaDataBuilder = metaDataBuilder.put(imd, true);
        routingTableBuilder.addAsNew(imd);

        MetaData metaData = metaDataBuilder.build();
        clusterState = ClusterState.builder(clusterState).metaData(metaData).routingTable(routingTableBuilder.build()).build();
        RoutingAllocation.Result routingResult = service.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("restart all the primary shards, replicas will start initializing");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();
        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("start the replica shards");
        routingNodes = clusterState.getRoutingNodes();
        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("complete rebalancing");
        return applyStartedShardsUntilNoChange(clusterState, service);
    }

    private ClusterState removeNodes(ClusterState clusterState, AllocationService service, int numNodes) {
        logger.info("Removing [{}] nodes", numNodes);
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        ArrayList<DiscoveryNode> discoveryNodes = CollectionUtils.iterableAsArrayList(clusterState.nodes());
        Collections.shuffle(discoveryNodes, random());
        for (DiscoveryNode node : discoveryNodes) {
            nodes.remove(node.getId());
            numNodes--;
            if (numNodes <= 0) {
                break;
            }
        }

        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();
        clusterState = ClusterState.builder(clusterState)
            .routingResult(service.deassociateDeadNodes(clusterState, true, "reroute")).build();

        logger.info("start all the primary shards, replicas will start initializing");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();
        RoutingAllocation.Result routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("start the replica shards");
        routingNodes = clusterState.getRoutingNodes();
        routingResult = service.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("rebalancing");
        routingResult = service.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(routingResult).build();

        logger.info("complete rebalancing");
        clusterState = applyStartedShardsUntilNoChange(clusterState, service);

        return clusterState;
    }
}
