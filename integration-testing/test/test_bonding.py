from test.cl_node.casperlabsnode import BONDING_CONTRACT, UNBONDING_CONTRACT
from test.cl_node.client_parser import parse_show_block
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.casperlabs_network import  OneNodeNetwork


def bond_to_the_network(network: OneNodeNetwork, contract: str, bond_amount: int):
    network.add_new_node_to_network()
    assert len(network.docker_nodes) == 2, "Total number of nodes should be 2."
    node0, node1 = network.docker_nodes
    block_hash = node1.bond(
        session_contract=contract, payment_contract=contract, amount=bond_amount
    )
    return block_hash


def assert_pre_state_of_network(network: OneNodeNetwork, stake: int):
    node0 = network.docker_nodes[0]
    blocks = parse_show_blocks(node0.client.show_blocks(1000))
    assert len(blocks) == 1
    genesis_block = blocks[0]
    item = list(
        filter(
            lambda x: x.stake == stake and x.validator_public_key == node0.from_address,
            genesis_block.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 0


def test_bonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network, bonding_amount)
    block_hash = bond_to_the_network(one_node_network, BONDING_CONTRACT, bonding_amount)
    assert block_hash is not None
    node1 = one_node_network.docker_nodes[1]
    block1 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block1)
    public_key = node1.from_address
    item = list(
        filter(
            lambda x: x.stake == bonding_amount and x.validator_public_key == public_key,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 1


def test_invalid_bonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # 190 is current total staked amount.
    bonding_amount = (190 * 1000) + 1
    block_hash = bond_to_the_network(
        one_node_network, BONDING_CONTRACT, bonding_amount
    )
    assert block_hash is not None
    node1 = one_node_network.docker_nodes[1]
    block1 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block1)
    public_key = node1.from_address
    item = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 0


def test_unbonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network, bonding_amount)
    block_hash = bond_to_the_network(one_node_network, BONDING_CONTRACT, bonding_amount)
    assert block_hash is not None
    node1 = one_node_network.docker_nodes[1]
    public_key = node1.from_address
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=None,
    )

    assert block_hash2 is not None
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == bonding_amount and x.validator_public_key == public_key,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 0


def test_invalid_unbonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    bonding_amount = 2000
    assert_pre_state_of_network(one_node_network, bonding_amount)
    block_hash = bond_to_the_network(one_node_network, BONDING_CONTRACT, bonding_amount)
    assert block_hash is not None
    node1 = one_node_network.docker_nodes[1]
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=1985,
    )

    assert block_hash2 is not None
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == bonding_amount and x.validator_public_key == node1.from_address,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 1
