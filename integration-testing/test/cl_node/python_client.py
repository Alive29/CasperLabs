from typing import Optional
import logging

from test.cl_node.client_base import CasperLabsClient
from casper_client import CasperClient


class PythonClient(CasperLabsClient):

    def __init__(self, node: 'DockerNode'):
        self.node = node
        self.client = CasperClient(host=self.node.container_name,
                                   internal_port=self.node.grpc_internal_docker_port,
                                   port=self.node.grpc_external_docker_port)
        logging.info(f'PythonClient(host={self.node.container_name}, '
                     f'port={self.node.grpc_external_docker_port}, '
                     f'internal_port={self.node.grpc_internal_docker_port})')

    @property
    def client_type(self) -> str:
        return 'python'

    def deploy(self,
               from_address: str = "00000000000000000000000000000000",
               gas_limit: int = 1000000,
               gas_price: int = 1,
               nonce: int = 0,
               session_contract: Optional[str] = 'test_helloname.wasm',
               payment_contract: Optional[str] = 'test_helloname.wasm') -> str:

        resources_path = self.node.resources_folder
        session_contract_path = str(resources_path / session_contract)
        payment_contract_path = str(resources_path / payment_contract)
        logging.info(f'PY_CLIENT.deploy(from_address={from_address}, gas_limit={gas_limit}, gas_price={gas_price}, '
                     f'payment_contract={payment_contract_path}, session_contract={session_contract_path}, '
                     f'nonce={nonce})')
        return self.client.deploy(from_address.encode('UTF-8'), gas_limit, gas_price,
                                  payment_contract_path, session_contract_path, nonce)

    def propose(self) -> str:
        logging.info(f'PY_CLIENT.propose() for {self.node.container_name}')
        return self.client.propose()

    def show_block(self, block_hash: str) -> str:
        pass

    def show_blocks(self, depth: int):
        return self.client.showBlocks(depth)

    def get_blocks_count(self, depth: int) -> int:
        block_count = sum([1 for _ in self.show_blocks(depth)])
        return block_count
