use common::key::Key;
use common::value::Value;
use execution::{exec, Error as ExecutionError};
use parking_lot::Mutex;
use std::collections::HashMap;
use std::marker::PhantomData;
use storage::error::{Error as StorageError, RootNotFound};
use storage::gs::{DbReader, ExecutionEffect};
use storage::history::*;
use storage::transform::Transform;
use vm::wasm_costs::WasmCosts;
use wasm_prep::process;

pub struct EngineState<R, H>
where
    R: DbReader,
    H: History<R>,
{
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Mutex<H>,
    wasm_costs: WasmCosts,
    _phantom: PhantomData<R>,
}

pub enum ExecutionResult {
    Success(ExecutionEffect),
    Failure(Error),
}

#[derive(Debug)]
pub enum Error {
    PreprocessingError(String),
    ExecError(ExecutionError),
    StorageError(StorageError),
    ValueNotFound(String),
}

impl From<wasm_prep::PreprocessingError> for Error {
    fn from(error: wasm_prep::PreprocessingError) -> Self {
        match error {
            wasm_prep::PreprocessingError::InvalidImportsError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::NoExportSection => {
                Error::PreprocessingError(String::from("No export section found."))
            }
            wasm_prep::PreprocessingError::NoImportSection => {
                Error::PreprocessingError(String::from("No import section found,"))
            }
            wasm_prep::PreprocessingError::DeserializeError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::OperationForbiddenByGasRules => {
                Error::PreprocessingError(String::from("Encountered operation forbidden by gas rules. Consult instruction -> metering config map."))
            }
            wasm_prep::PreprocessingError::StackLimiterError => {
                Error::PreprocessingError(String::from("Wasm contract error: Stack limiter error."))

            }
        }
    }
}

impl From<StorageError> for Error {
    fn from(error: StorageError) -> Self {
        Error::StorageError(error)
    }
}

impl From<ExecutionError> for Error {
    fn from(error: ExecutionError) -> Self {
        Error::ExecError(error)
    }
}

impl<G, R> EngineState<R, G>
where
    G: History<R>,
    R: DbReader,
{
    pub fn new(state: G) -> EngineState<R, G> {
        EngineState {
            state: Mutex::new(state),
            wasm_costs: WasmCosts::new(),
            _phantom: PhantomData,
        }
    }

    pub fn query_state(
        &self,
        state_hash: [u8; 32],
        base_key: Key,
        path: &[String],
    ) -> Result<Value, Error> {
        let mut tc = self.state.lock().checkout(state_hash)?;
        let base_value = tc.read(base_key)?;
        let mut full_path = String::new();
        for p in path {
            full_path.push_str(p);
        }
        let not_found_error = Error::ValueNotFound(full_path);

        let maybe_value =
            path.iter()
                .try_fold(base_value, |curr_value, name| -> Result<Value, Error> {
                    match curr_value {
                        Value::Acct(account) => {
                            let key = account
                                .urefs_lookup()
                                .get(name)
                                .ok_or(Error::ValueNotFound(String::new()))?;
                            tc.read(*key).map_err(|e| e.into())
                        }

                        Value::Contract { known_urefs, .. } => {
                            let key = known_urefs
                                .get(name)
                                .ok_or(Error::ValueNotFound(String::new()))?;
                            tc.read(*key).map_err(|e| e.into())
                        }

                        _ => Err(Error::ValueNotFound(String::new())),
                    }
                });

        //Can't return `not_found_error` directly in body above because
        //of compiler error about moving out value in FnMut closure
        match maybe_value {
            Err(_) => Err(not_found_error),
            ok @ Ok(_) => ok,
        }
    }

    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy(
        &self,
        module_bytes: &[u8],
        address: [u8; 20],
        timestamp: u64,
        nonce: u64,
        prestate_hash: [u8; 32],
        gas_limit: &u64,
    ) -> Result<ExecutionResult, RootNotFound> {
        match process(module_bytes, &self.wasm_costs) {
            Err(error) => Ok(ExecutionResult::Failure(error.into())),
            Ok(module) => {
                let mut tc: storage::gs::TrackingCopy<R> =
                    self.state.lock().checkout(prestate_hash)?;
                match exec(module, address, timestamp, nonce, gas_limit, &mut tc) {
                    Ok(ee) => Ok(ExecutionResult::Success(ee)),
                    Err(error) => Ok(ExecutionResult::Failure(error.into())),
                }
            }
        }
    }

    pub fn apply_effect(
        &self,
        prestate_hash: [u8; 32],
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, RootNotFound> {
        self.state.lock().commit(prestate_hash, effects)
    }
}
