use crate::transform::Transform;
use common::key::Key;
use common::value::Value;
use common::bytesrepr::*;
use error::Error;
use gs::*;
use history::*;
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::Mutex;
use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;

/// In memory representation of the versioned global state
/// store - stores a snapshot of the global state at the specific block
/// history - stores all the snapshots of the global state
pub struct InMemGS {
    store: Arc<Mutex<HashMap<Key, Value>>>,
    history: Arc<Mutex<HashMap<[u8;32], HashMap<Key,Value>>>>,
}

impl InMemGS {
    pub fn new() -> InMemGS {
        InMemGS {
            store: Arc::new(Mutex::new(HashMap::new())),
            history: Arc::new(Mutex::new(HashMap::new())),
        }
    }
}

impl DbReader for InMemGS {
    fn get(&self, k: &Key) -> Result<Value, Error> {
        match self.store.lock().get(k) {
            None => Err(Error::KeyNotFound { key: *k }),
            Some(v) => Ok(v.clone()),
        }
    }
}

impl History<Self> for InMemGS {
    fn checkout_multiple(&self, prestate_hashes: Vec<[u8; 32]>) -> Result<TrackingCopy<InMemGS>, Error> {
        let missing_root = prestate_hashes.iter().find(|root| !self.history.lock().contains_key(root.clone()));
        match missing_root {
            Some(missing) => Err(Error::RootNotFound(missing.clone())),
            None => {
                let mut new_root: HashMap<Key, Value> = HashMap::new();
                for root in prestate_hashes.iter() {
                    let snapshot = self.history.lock().get(root).unwrap().clone();
                    new_root.extend(snapshot);
                };
                let mut store = self.store.lock();
                *store = new_root;
                Ok(TrackingCopy::new(self))
            }
        }
    }

    /// **WARNING**
    /// This will drop any changes made to `active_store` and replace it with
    /// the state under passed hash.
    fn checkout(&self, prestate_hash: [u8; 32]) -> Result<TrackingCopy<InMemGS>, Error> {
        if(!self.history.lock().contains_key(&prestate_hash)) {
            Err(Error::RootNotFound(prestate_hash))
        } else {
            let mut store = self.store.lock();
            *store = self.history.lock().get(&prestate_hash).unwrap().clone();
            Ok(TrackingCopy::new(self))
        }
    }

    fn commit(&mut self, tracking_copy: ExecutionEffect) -> Result<[u8; 32], Error> {
        tracking_copy.1.into_iter()
            .try_fold((), |_, (k, t)| {
                let maybe_curr = self.store.lock().remove(&k);
                match maybe_curr {
                    None => match t {
                        Transform::Write(v) => {
                            let _ = self.store.lock().insert(k, v);
                            Ok(())
                        }
                        _ => Err(Error::KeyNotFound { key: k }),
                    },
                    Some(curr) => {
                        let new_value = t.apply(curr)?;
                        let _ = self.store.lock().insert(k, new_value);
                        Ok(())
                    }
                }
            })
            .and_then(|_| {
                //TODO(mateusz.gorski): Awful waste of time and space
                let active_store = self.store.lock().clone();
                let hash = self.get_root_hash()?;
                self.history.lock().insert(hash, active_store);
                Ok(hash)
            })
    }

    //TODO(mateusz.gorski): I know this is not efficient and we should be caching these values
    //but for the time being it should be enough.
    fn get_root_hash(&self) -> Result<[u8; 32], Error> {
        let mut data: Vec<u8> = Vec::new();
        for (k, v) in self.store.lock().iter() {
            data.extend(k.to_bytes());
            data.extend(v.to_bytes());
        };
        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(data);
        let mut hash_bytes = [0; 32];
        hasher.variable_result(|hash| hash_bytes.clone_from_slice(hash));
        Ok(hash_bytes)
    }
}


