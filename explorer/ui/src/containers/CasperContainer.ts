import { computed, observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import StorageCell from '../lib/StorageCell';
import FaucetService from '../services/FaucetService';
import CasperService from '../services/CasperService';
import {
  DeployInfo,
  BlockInfo
} from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { GrpcError } from '../services/Errors';
import { grpc } from '@improbable-eng/grpc-web';

export class DagStep {
  constructor(private container: CasperContainer) {}

  private step = (f: () => number) => () => {
    this.maxRank = f();
    this.container.refreshBlockDag();
    this.container.selectedBlock = undefined;
  };

  private get maxRank() {
    return this.container.maxRank;
  }

  private get dagDepth() {
    return this.container.dagDepth;
  }

  private set maxRank(rank: number) {
    this.container.maxRank = rank;
  }

  private get currentMaxRank() {
    let blockRank =
      this.container.hasBlocks &&
      this.container
        .blocks![0].getSummary()!
        .getHeader()!
        .getRank();
    return this.maxRank === 0 && blockRank ? blockRank : this.maxRank;
  }

  first = this.step(() => this.dagDepth - 1);

  prev = this.step(() =>
    this.maxRank === 0 && this.currentMaxRank <= this.dagDepth
      ? 0
      : this.currentMaxRank > this.dagDepth
      ? this.currentMaxRank - this.dagDepth
      : this.currentMaxRank
  );

  next = this.step(() => this.currentMaxRank + this.dagDepth);

  last = this.step(() => 0);
}

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {
  private _faucetRequests = new StorageCell<FaucetRequest[]>(
    'faucet-requests',
    []
  );

  // Start polling for status when we add a new faucet request.
  private faucetStatusTimerId = 0;
  private faucetStatusInterval = 10 * 1000;

  // Block DAG
  @observable blocks: BlockInfo[] | null = null;
  @observable selectedBlock: BlockInfo | undefined = undefined;
  @observable dagDepth = 10;
  @observable maxRank = 0;

  get minRank() {
    return Math.max(0, this.maxRank - this.dagDepth + 1);
  }

  get hasBlocks() {
    return this.blocks ? this.blocks.length > 0 : false;
  }

  dagStep = new DagStep(this);

  constructor(
    private errors: ErrorContainer,
    private faucetService: FaucetService,
    private casperService: CasperService,
    // Callback when the faucet status finished so we can update the balances.
    private onFaucetStatusChange: () => void
  ) {}

  /** Ask the faucet for tokens for a given account. */
  async requestTokens(account: UserAccount) {
    const request = async () => {
      const deployHash = await this.faucetService.requestTokens(
        account.publicKeyBase64
      );
      this.monitorFaucetRequest(account, deployHash);
    };
    this.errors.capture(request());
  }

  /** List faucet requests we sent earlier. */
  @computed get faucetRequests() {
    return this._faucetRequests.get;
  }

  private monitorFaucetRequest(account: UserAccount, deployHash: DeployHash) {
    const request = { timestamp: new Date(), account, deployHash };
    const requests = [request].concat(this._faucetRequests.get);
    this._faucetRequests.set(requests);
    this.startPollingFaucetStatus();
  }

  private startPollingFaucetStatus() {
    if (this.faucetStatusTimerId === 0) {
      this.faucetStatusTimerId = window.setInterval(
        () => this.refreshFaucetRequestStatus(),
        this.faucetStatusInterval
      );
    }
  }

  async refreshFaucetRequestStatus() {
    const requests = this._faucetRequests.get;
    let updated = false;
    let anyNeededUpdate = false;
    for (let req of requests) {
      const needsUpdate =
        typeof req.deployInfo === 'undefined' ||
        req.deployInfo!.processingResultsList.length === 0;

      if (needsUpdate) {
        anyNeededUpdate = true;
        const info = await this.errors.withCapture(
          this.tryGetDeployInfo(req.deployHash)
        );
        if (info != null) {
          req.deployInfo = info.toObject();
          updated = true;
        }
      }
    }
    if (updated) {
      this._faucetRequests.set(requests);
      this.onFaucetStatusChange();
    }
    if (!anyNeededUpdate) {
      window.clearTimeout(this.faucetStatusTimerId);
    }
  }

  private async tryGetDeployInfo(
    deployHash: DeployHash
  ): Promise<DeployInfo | null> {
    try {
      return await this.casperService.getDeployInfo(deployHash);
    } catch (err) {
      if (err instanceof GrpcError) {
        if (err.code === grpc.Code.NotFound)
          // We connected to a node that doesn't have it yet.
          return null;
      }
      throw err;
    }
  }

  async refreshBlockDag() {
    this.errors.capture(
      this.casperService
        .getBlockInfos(this.dagDepth, this.maxRank)
        .then(blocks => {
          this.blocks = blocks;
        })
    );
  }
}

// Record of a request we submitted.
export interface FaucetRequest {
  timestamp: Date;
  account: UserAccount;
  deployHash: DeployHash;
  // Assigned later when the data becomes available.
  deployInfo?: DeployInfo.AsObject;
}

export default CasperContainer;
