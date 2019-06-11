import threading
import time
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.docker_node import DockerNode
from test.cl_node.errors import NonZeroExitCodeError
from typing import List
import pytest
import logging

from . import conftest
from .cl_node.casperlabs_network import ThreeNodeNetwork, CustomConnectionNetwork
from .cl_node.casperlabsnode import extract_block_hash_from_propose_output
from .cl_node.common import random_string
from .cl_node.wait import wait_for_blocks_count_at_least, wait_for_peers_count_at_least


class DeployThread(threading.Thread):
    def __init__(self,
            node: DockerNode,
            batches_of_contracts: List[List[str]],
            max_attempts,
            retry_seconds) -> None:
        threading.Thread.__init__(self)
        self.node = node
        self.batches_of_contracts = batches_of_contracts
        self.deployed_blocks_hashes = set()
        self.max_attempts = max_attempts
        self.retry_seconds = retry_seconds

    def propose(self):
        propose_output = self.node.client.propose()
        return extract_block_hash_from_propose_output(propose_output)

    def run(self) -> None:
        for batch in self.batches_of_contracts:
            for contract in batch:
                assert 'Success' in self.node.client.deploy(session_contract = contract,
                                                            payment_contract = contract,
                                                            private_key="validator-0-private.pem",
                                                            public_key="validator-0-public.pem")
            # With many threads using the same account the nonces will be interleaved.
            # Only one node can propose at a time, the others have to wait until they
            # receive the block and then try proposing again.
            attempt = 0
            while True:
                try:
                    block_hash = self.propose()
                    self.deployed_blocks_hashes.add(block_hash)
                except NonZeroExitCodeError:
                    if attempt < self.max_attempts:
                        logging.debug("Could not propose; retrying later.")
                        attempt += 1
                        time.sleep(self.retry_seconds)
                    else:
                        logging.debug("Could not propose; no more retries!")
                        raise ex
                else:
                    break




@pytest.fixture()
def nodes(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as network:
        network.create_cl_network()
        # Wait for the genesis block reaching each node.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
        yield network.docker_nodes



@pytest.mark.parametrize("contract_paths, expected_number_of_blocks", [
                         ([['test_helloname.wasm'],['test_helloworld.wasm']], 7),
                         ])
def test_block_propagation(nodes,
                           contract_paths: List[List[str]],
                           expected_number_of_blocks):
    """
    Feature file: consensus.feature
    Scenario: test_helloworld.wasm deploy and propose by all nodes and stored in all nodes blockstores
    """

    deploy_threads = [DeployThread(node, contract_paths, max_attempts = 5, retry_seconds = 3) for node in nodes]

    for t in deploy_threads:
        t.start()

    for t in deploy_threads:
        t.join()

    for node in nodes:
        wait_for_blocks_count_at_least(node, expected_number_of_blocks, expected_number_of_blocks * 2, node.timeout)

    for node in nodes:
        blocks = parse_show_blocks(node.client.show_blocks(expected_number_of_blocks * 100))
        # What propose returns is first 10 characters of block hash, so we can compare only first 10 charcters.
        blocks_hashes = set([b.block_hash[:10] for b in blocks])
        for t in deploy_threads:
            assert t.deployed_blocks_hashes.issubset(blocks_hashes), \
                   f"Not all blocks deployed and proposed on {t.node.container_name} were propagated to {node.container_name}"



def deploy_and_propose(node, contract):
    assert 'Success' in node.client.deploy(session_contract = contract,
                                           payment_contract = contract,
                                           private_key="validator-0-private.pem",
                                           public_key="validator-0-public.pem")
    propose_output = node.client.propose()
    return extract_block_hash_from_propose_output(propose_output)


@pytest.fixture()
def not_all_connected_directly_nodes(docker_client_fixture):
    """
    node0 -- node1 -- node2
    """
    with CustomConnectionNetwork(docker_client_fixture) as network:
        # All nodes need to be connected to bootstrap in order to download the genesis block.
        network.create_cl_network(3, [(0, 1), (1, 2), (0, 2)])
        # Wait for the genesis block reaching each node.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)
        # All nodes have the genesis block now, so we can disconnect one from the bootstrap.
        network.disconnect((0, 2))
        yield network.docker_nodes


def test_blocks_infect_network(not_all_connected_directly_nodes):
    """
    Feature file: block_gossiping.feature
    Scenario: Blocks 'infect' the network and nodes 'closest' to the propose see the blocks first.
    """
    first, last = not_all_connected_directly_nodes[0], not_all_connected_directly_nodes[-1]

    block_hash = deploy_and_propose(first, 'test_helloname.wasm')
    wait_for_blocks_count_at_least(last, 2, 2)
    blocks = parse_show_blocks(last.client.show_blocks(2))
    blocks_hashes = set([b.block_hash[:10] for b in blocks])
    assert block_hash in blocks_hashes


@pytest.fixture()
def four_nodes_network(docker_client_fixture):
    with CustomConnectionNetwork(docker_client_fixture) as network:

        # Initially all nodes are connected to each other
        network.create_cl_network(4, [(i, j) for i in range(4) for j in range(4) if i != j and i < j])

        # Wait till all nodes have the genesis block.
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, 1, 1, node.timeout)

        yield network

C = ["test_helloname.wasm", "test_mailinglistdefine.wasm", "test_helloworld.wasm"]

def test_network_partition_and_rejoin(four_nodes_network):
    """
    Feature file: block_gossiping.feature
    Scenario: Network partition occurs and rejoin occurs
    """
    # Partition the network so node0 connected to node1 and node2 connected to node3 only.
    connections_between_partitions = [(i, j) for i in (0,1) for j in (2,3)]

    logging.info("DISCONNECT PARTITIONS")
    for connection in connections_between_partitions:
        four_nodes_network.disconnect(connection)

    nodes = four_nodes_network.docker_nodes
    n = len(nodes)
    partitions = nodes[:int(n/2)], nodes[int(n/2):]

    # Propose separately in each partition. They should not see each others' blocks,
    # so everyone has the genesis plus the 1 block proposed in its partition.
    deploy_and_propose(partitions[0][0], C[0])
    deploy_and_propose(partitions[1][0], C[1])

    for node in nodes:
        wait_for_blocks_count_at_least(node, 2, 2, node.timeout * 2)

    logging.info("CONNECT PARTITIONS")
    #four_nodes_network.connect((p[0] for p in partitions))
    for connection in connections_between_partitions:
        four_nodes_network.connect(connection)

    #for node in nodes: wait_for_peers_count_at_least(node, n-1, node.timeout)
    logging.info("PARTITIONS CONNECTED")

    # NOTE: Currently `NodeDiscoveryImpl.alivePeersInAscendingDistance` pings the peers
    # before returning a list, from which we take a random subset for gossiping,
    # so when we do a deploy the node will see the peers it previously knew about
    # as alive as soon as the network is re-established.
    # However, NODE-561 may change this and push pinging into the background in
    # which case it may take a bit longer for the peers to pick up each outhers's presence.

    # When we propose a node in partition[0] it should propagate to partition[1],
    # however, nodes in partition[0] will still not see blocks from partition[1]
    # until they also propose a new one on top of the block the created during
    # the network outage.
    deploy_and_propose(nodes[0], C[2])

    # NOTE: Currently the node closes the channel only after it has detected a failure,
    # but the syncing will not retry it. A second attempt will create a new channel though.
    deploy_and_propose(nodes[0], C[2])

    for node in partitions[0]:
        logging.info(f"CHECK {node} HAS ALL BLOCKS")
        wait_for_blocks_count_at_least(node, 4, 4, node.timeout * 2)

    for node in partitions[1]:
        logging.info(f"CHECK {node} HAS ALL NODES")
        wait_for_blocks_count_at_least(node, 5, 5, node.timeout * 2)

