use core::fmt::Write;
use std::collections::btree_map::BTreeMap;
use std::collections::HashMap;
use std::fmt;

use rand::RngCore;
use rand_chacha::ChaChaRng;

use common::bytesrepr::ToBytes;
use common::key::Key;
use common::uref::{AccessRights, URef};
use common::value::{Contract, U512, Value};
use common::value::account::{PublicKey, PurseId};
use engine_state::execution_effect::ExecutionEffect;
use engine_state::op::Op;
use engine_state::utils::WasmiBytes;
use execution;
use shared::init;
use shared::newtypes::Blake2bHash;
use shared::transform::{Transform, TypeMismatch};
use storage::global_state::CommitResult;

pub const POS_PURSE: &str = "pos_purse";

fn create_uref<R: RngCore>(rng: &mut R) -> URef {
    let mut buff = [0u8; 32];
    rng.fill_bytes(&mut buff);
    URef::new(buff, AccessRights::READ_ADD_WRITE)
}

fn create_mint_effects(
    rng: &mut ChaChaRng,
    genesis_account_addr: [u8; 32],
    initial_tokens: U512,
    mint_code_bytes: WasmiBytes,
    protocol_version: u64,
) -> Result<HashMap<Key, Value>, execution::Error> {
    let mut tmp: HashMap<Key, Value> = HashMap::new();

    // Create (public_uref, mint_contract_uref)

    let public_uref = create_uref(rng);

    let mint_contract_uref = create_uref(rng);

    // Store (public_uref, mint_contract_uref) in global state

    tmp.insert(
        Key::URef(public_uref),
        Value::Key(Key::URef(mint_contract_uref)),
    );

    let purse_id_uref = create_uref(rng);

    // Create genesis genesis_account
    let genesis_account = {
        // All blessed / system contract public urefs MUST be added to the genesis account's known_urefs
        // TODO: do we need to deal with NamedKey ???
        let known_urefs = &[
            (String::from("mint"), Key::URef(public_uref)),
            (
                mint_contract_uref.as_string(),
                Key::URef(mint_contract_uref),
            ),
        ];
        let purse_id = PurseId::new(purse_id_uref);
        init::create_genesis_account(genesis_account_addr, purse_id, known_urefs)
    };

    // Store (genesis_account_addr, genesis_account) in global state

    tmp.insert(
        Key::Account(genesis_account_addr),
        Value::Account(genesis_account),
    );

    // Initializing and persisting mint

    // Create (purse_id_local_key, balance_uref) (for mint-local state)

    let purse_id_local_key = {
        let seed = mint_contract_uref.addr();
        let local_key = purse_id_uref.addr();
        let local_key_bytes = &local_key.to_bytes()?;
        Key::local(seed, local_key_bytes)
    };

    let balance_uref = create_uref(rng);

    let balance_uref_key = Key::URef(balance_uref);

    // Store (purse_id_local_key, balance_uref_key) in local state

    tmp.insert(purse_id_local_key, Value::Key(balance_uref_key));

    // Create balance

    let balance: Value = Value::UInt512(initial_tokens);

    // Store (balance_uref_key, balance) in local state

    tmp.insert(balance_uref_key, balance);

    let mint_known_urefs = {
        let mut ret: BTreeMap<String, Key> = BTreeMap::new();
        ret.insert(balance_uref.as_string(), balance_uref_key);
        ret.insert(
            mint_contract_uref.as_string(),
            Key::URef(mint_contract_uref),
        );
        ret
    };

    let mint_contract: Contract =
        Contract::new(mint_code_bytes.into(), mint_known_urefs, protocol_version);

    // Store (mint_contract_uref, mint_contract) in global state

    tmp.insert(
        Key::URef(mint_contract_uref),
        Value::Contract(mint_contract),
    );

    Ok(tmp)
}

fn create_pos_effects(
    rng: &mut ChaChaRng,
    pos_code: WasmiBytes,
    genesis_validators: Vec<(PublicKey, U512)>,
    protocol_version: u64,
) -> Result<HashMap<Key, Value>, execution::Error> {
    let mut tmp: HashMap<Key, Value> = HashMap::new();

    // Create (public_pos_uref, pos_contract_uref)
    let public_pos_address = create_uref(rng);
    let pos_uref = create_uref(rng);
    // Create PoS purse.
    let pos_purse = create_uref(rng);

    // Mateusz: Maybe we could make `public_pos_address` a Key::Hash after all.
    // Store public PoS address -> PoS contract relation
    tmp.insert(
        Key::URef(public_pos_address),
        Value::Key(Key::URef(pos_uref)),
    );

    // Add genesis validators to PoS contract object.
    // For now, we are storing validators in `known_urefs` map of the PoS contract
    // in the form: key: "v_{validator_pk}_{validator_stake}", value: doesn't matter.
    let mut known_urefs: BTreeMap<String, Key> = genesis_validators
        .iter()
        .map(|(pub_key, balance)| {
            let key_bytes = pub_key.value();
            let mut hex_key = String::with_capacity(64);
            for byte in &key_bytes[..32] {
                write!(hex_key, "{:02x}", byte).unwrap();
            }
            let mut uref = String::new();
            uref.write_fmt(format_args!("v_{}_{}", hex_key, balance))
                .unwrap();
            uref
        })
        .map(|key| (key, Key::Hash([0u8; 32])))
        .collect();

    known_urefs.insert(POS_PURSE.to_string(), Key::URef(pos_purse));

    // Create PoS Contract object.
    let contract = Contract::new(pos_code.into(), known_urefs, protocol_version);

    // Store PoS code under `pos_uref`.
    tmp.insert(Key::URef(pos_uref), Value::Contract(contract));

    Ok(tmp)
}

// TODO: Post devnet, make genesis creation regular contract execution.
pub fn create_genesis_effects(
    genesis_account_addr: [u8; 32],
    initial_tokens: U512,
    mint_code_bytes: WasmiBytes,
    pos_code_bytes: WasmiBytes,
    genesis_validators: Vec<(PublicKey, U512)>,
    protocol_version: u64,
) -> Result<ExecutionEffect, execution::Error> {
    let mut rng = execution::create_rng(genesis_account_addr, 0);

    let mint_effects = create_mint_effects(
        &mut rng,
        genesis_account_addr,
        initial_tokens,
        mint_code_bytes,
        protocol_version,
    )?;

    let pos_effects = create_pos_effects(
        &mut rng,
        pos_code_bytes,
        genesis_validators,
        protocol_version,
    )?;

    let mut execution_effect: ExecutionEffect = Default::default();

    for (k, v) in mint_effects.into_iter().chain(pos_effects.into_iter()) {
        let k = if let Key::URef(_) = k {
            k.normalize()
        } else {
            k
        };
        execution_effect.ops.insert(k, Op::Write);
        execution_effect.transforms.insert(k, Transform::Write(v));
    }

    Ok(execution_effect)
}

pub enum GenesisResult {
    RootNotFound,
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
    Success {
        post_state_hash: Blake2bHash,
        effect: ExecutionEffect,
    },
}

impl fmt::Display for GenesisResult {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            GenesisResult::RootNotFound => write!(f, "Root not found"),
            GenesisResult::KeyNotFound(key) => write!(f, "Key not found: {}", key),
            GenesisResult::TypeMismatch(type_mismatch) => {
                write!(f, "Type mismatch: {:?}", type_mismatch)
            }
            GenesisResult::Success {
                post_state_hash,
                effect,
            } => write!(f, "Success: {} {:?}", post_state_hash, effect),
        }
    }
}

impl GenesisResult {
    pub fn from_commit_result(commit_result: CommitResult, effect: ExecutionEffect) -> Self {
        match commit_result {
            CommitResult::RootNotFound => GenesisResult::RootNotFound,
            CommitResult::KeyNotFound(key) => GenesisResult::KeyNotFound(key),
            CommitResult::TypeMismatch(type_mismatch) => GenesisResult::TypeMismatch(type_mismatch),
            CommitResult::Success(post_state_hash) => GenesisResult::Success {
                post_state_hash,
                effect,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::btree_map::BTreeMap;
    use std::collections::HashMap;

    use common::bytesrepr::ToBytes;
    use common::key::{addr_to_hex, Key};
    use common::value::{Contract, U512, Value};
    use common::value::account::PublicKey;
    use engine_state::create_genesis_effects;
    use engine_state::utils::WasmiBytes;
    use execution;
    use shared::test_utils;
    use shared::transform::Transform;
    use wasm_prep::wasm_costs::WasmCosts;

    use super::{create_uref, POS_PURSE};

    const GENESIS_ACCOUNT_ADDR: [u8; 32] = [6u8; 32];
    const PROTOCOL_VERSION: u64 = 1;
    const EXPECTED_GENESIS_TRANSFORM_COUNT: usize = 7; // 5 writes for Mint and 2 for PoS.
    const INITIAL_BALANCE: &str = "1000";

    fn get_initial_tokens(initial_balance: &str) -> U512 {
        U512::from_dec_str(initial_balance).expect("should create U512")
    }

    fn get_mint_code_bytes() -> WasmiBytes {
        let raw_bytes = test_utils::create_empty_wasm_module_bytes();
        WasmiBytes::new(raw_bytes.as_slice(), WasmCosts::free()).expect("should create wasmi bytes")
    }

    fn get_pos_code_bytes() -> WasmiBytes {
        let raw_bytes = test_utils::create_empty_wasm_module_bytes();
        WasmiBytes::new(raw_bytes.as_slice(), WasmCosts::free()).expect("should create wasmi bytes")
    }

    fn get_genesis_transforms() -> HashMap<Key, Transform> {
        let initial_tokens = get_initial_tokens(INITIAL_BALANCE);

        let mint_code_bytes = get_mint_code_bytes();
        let pos_code_bytes = get_pos_code_bytes();
        let genesis_validators = Vec::new();

        create_genesis_effects(
            GENESIS_ACCOUNT_ADDR,
            initial_tokens,
            mint_code_bytes,
            pos_code_bytes,
            genesis_validators,
            PROTOCOL_VERSION,
        )
        .expect("should create effects")
        .transforms
    }

    fn is_write(transform: &Transform) -> bool {
        if let Transform::Write(_) = transform {
            true
        } else {
            false
        }
    }

    fn extract_transform_key(effects: HashMap<Key, Transform>, key: &Key) -> Option<Key> {
        if let Transform::Write(Value::Key(value)) =
            effects.get(&key.normalize()).expect("should have value")
        {
            Some(*value)
        } else {
            None
        }
    }

    fn extract_transform_u512(effects: HashMap<Key, Transform>, key: &Key) -> Option<U512> {
        if let Transform::Write(Value::UInt512(value)) =
            effects.get(key).expect("should have value")
        {
            Some(*value)
        } else {
            None
        }
    }

    fn extract_transform_contract_bytes(
        effects: HashMap<Key, Transform>,
        key: &Key,
    ) -> Option<Contract> {
        if let Transform::Write(Value::Contract(value)) =
            effects.get(&key.normalize()).expect("should have value")
        {
            Some(value.to_owned())
        } else {
            None
        }
    }

    #[test]
    fn create_genesis_effects_creates_expected_effects() {
        let transforms = get_genesis_transforms();

        assert_eq!(transforms.len(), EXPECTED_GENESIS_TRANSFORM_COUNT);

        assert!(transforms.iter().all(|(_, effect)| is_write(effect)));
    }

    #[test]
    fn create_genesis_effects_stores_mint_contract_uref_at_public_uref() {
        // given predictable uref(s) should be able to retrieve values and assert expected

        let mut rng = execution::create_rng(GENESIS_ACCOUNT_ADDR, 0);

        let public_uref = create_uref(&mut rng);

        let public_uref_key = Key::URef(public_uref);

        let transforms = get_genesis_transforms();

        assert!(
            transforms.contains_key(&public_uref_key.normalize()),
            "should have expected public_uref"
        );

        let actual = extract_transform_key(transforms, &public_uref_key)
            .expect("transform was not a write of a key");

        let mint_contract_uref_key = create_uref(&mut rng);

        // the value under the outer mint_contract_uref should be a key value pointing at
        // the current contract bytes
        assert_eq!(
            actual,
            Key::URef(mint_contract_uref_key),
            "invalid mint contract indirection"
        );
    }

    #[test]
    fn create_genesis_effects_stores_mint_contract_code_at_mint_contract_uref() {
        let mut rng = execution::create_rng(GENESIS_ACCOUNT_ADDR, 0);

        let _public_uref = create_uref(&mut rng);

        let mint_contract_uref = create_uref(&mut rng);

        // this is passing as currently designed, but see bug: EE-380
        let mint_contract_uref_key = Key::URef(mint_contract_uref);

        let transforms = get_genesis_transforms();

        let actual = extract_transform_contract_bytes(transforms, &mint_contract_uref_key)
            .expect("transform was not a write of a key");

        let mint_code_bytes = get_mint_code_bytes();

        let _purse_id_uref = create_uref(&mut rng);

        // this is passing as currently designed, but see bug: EE-380
        let balance_uref = create_uref(&mut rng);

        let balance_uref_key = Key::URef(balance_uref);

        let mint_known_urefs = {
            let mut ret: BTreeMap<String, Key> = BTreeMap::new();
            ret.insert(
                mint_contract_uref.as_string(),
                Key::URef(mint_contract_uref),
            );
            ret.insert(balance_uref.as_string(), balance_uref_key);
            ret
        };

        let mint_contract: Contract = Contract::new(mint_code_bytes.into(), mint_known_urefs, 1);

        // the value under the mint_contract_uref_key should be the current contract bytes
        assert_eq!(actual, mint_contract, "invalid mint contract bytes");
    }

    #[test]
    fn create_genesis_effects_balance_uref_at_purse_id() {
        let mut rng = execution::create_rng(GENESIS_ACCOUNT_ADDR, 0);

        // Ignoring first URef, it's "public uref".
        let _ = create_uref(&mut rng);
        let mint_contract_uref = create_uref(&mut rng);

        let purse_id_uref = create_uref(&mut rng);

        let purse_id_local_key = {
            let seed = mint_contract_uref.addr();
            let local_key = purse_id_uref.addr();
            let local_key_bytes = &local_key.to_bytes().expect("should serialize");
            Key::local(seed, local_key_bytes)
        };

        let balance_uref = create_uref(&mut rng);

        let transforms = get_genesis_transforms();

        assert!(
            transforms.contains_key(&purse_id_local_key),
            "transforms should contain purse_id_local_key"
        );

        let actual = extract_transform_key(transforms, &purse_id_local_key)
            .expect("transform was not a write of a key");

        // the value under the outer mint_contract_uref should be a key value pointing at
        // the current contract bytes
        assert_eq!(
            actual,
            Key::URef(balance_uref),
            "invalid balance indirection"
        );
    }

    #[test]
    fn create_genesis_effects_balance_at_balance_uref() {
        let mut rng = execution::create_rng(GENESIS_ACCOUNT_ADDR, 0);

        let _public_uref = create_uref(&mut rng);
        let mint_contract_uref = create_uref(&mut rng);

        let purse_id_uref = create_uref(&mut rng);

        let purse_id_local_key = {
            let seed = mint_contract_uref.addr();
            let local_key = purse_id_uref.addr();
            let local_key_bytes = &local_key.to_bytes().expect("should serialize");
            Key::local(seed, local_key_bytes)
        };

        let balance_uref = create_uref(&mut rng);

        let transforms = get_genesis_transforms();

        assert!(
            transforms.contains_key(&purse_id_local_key),
            "transforms should contain purse_id_local_key"
        );

        let balance_uref_key = Key::URef(balance_uref).normalize();

        let actual = extract_transform_u512(transforms, &balance_uref_key)
            .expect("transform was not a write of a key");

        let initial_tokens = get_initial_tokens(INITIAL_BALANCE);

        // the value under the outer balance_uref_key should be a U512 value (the actual balance)
        assert_eq!(actual, initial_tokens, "invalid balance");
    }

    #[test]
    fn create_genesis_effects_stores_genesis_account_at_genesis_account_addr() {
        let account_key = Key::Account(GENESIS_ACCOUNT_ADDR);

        let transforms = get_genesis_transforms();

        let mint_public_uref = {
            let account_transform = transforms
                .get(&account_key)
                .expect("should have expected account");

            if let Transform::Write(Value::Account(account)) = account_transform {
                account.urefs_lookup().get("mint")
            } else {
                None
            }
            .expect("should have uref")
        };

        let mint_private_uref = {
            let mint_public_uref_transform = transforms
                .get(&mint_public_uref.normalize())
                .expect("should have mint public uref tranform");

            if let Transform::Write(Value::Key(key @ Key::URef(_))) = mint_public_uref_transform {
                Some(key)
            } else {
                None
            }
            .expect("should have uref")
        };

        let actual_mint_contract_bytes = {
            let mint_contract_transform = transforms
                .get(&mint_private_uref.normalize())
                .expect("should have expected uref");

            if let Transform::Write(Value::Contract(contract)) = mint_contract_transform {
                Some(contract.bytes())
            } else {
                None
            }
            .expect("should have contract")
        };

        let expected_mint_contract_bytes: Vec<u8> = get_mint_code_bytes().into();

        assert_eq!(
            actual_mint_contract_bytes,
            expected_mint_contract_bytes.as_slice()
        );
    }

    #[test]
    fn create_pos_effects() {
        let mut rng = execution::create_rng(GENESIS_ACCOUNT_ADDR, 0);

        let genesis_validator_public_key = PublicKey::new([0u8; 32]);
        let genesis_validator_stake = U512::from(1000);

        let genesis_validators =
            std::iter::once((genesis_validator_public_key, genesis_validator_stake)).collect();

        let public_pos_address = create_uref(&mut rng);

        let pos_contract_uref = create_uref(&mut rng);

        let pos_purse = create_uref(&mut rng);

        let pos_contract_bytes = get_pos_code_bytes();

        let pos_effects = {
            // Use new PRNG for the PoS effects process.
            let mut pos_rng = execution::create_rng(GENESIS_ACCOUNT_ADDR, 0);
            super::create_pos_effects(
                &mut pos_rng,
                pos_contract_bytes.clone(),
                genesis_validators,
                1,
            )
            .expect("Creating PoS effects in test should not fail.")
        };

        assert_eq!(
            pos_effects.get(&Key::URef(public_pos_address)),
            Some(&Value::Key(Key::URef(pos_contract_uref))),
            "Public URef should point at PoS contract URef."
        );

        let pos_contract = match pos_effects
            .get(&Key::URef(pos_contract_uref))
            .expect("PoS contract should be stored in GlobalState.")
        {
            Value::Contract(contract) => contract,
            _ => panic!("Expected Value::Contract"),
        };

        // rustc isn't smart enough to figure that out
        let pos_contract_raw: Vec<u8> = pos_contract_bytes.into();
        assert_eq!(pos_contract.bytes().to_vec(), pos_contract_raw);
        assert_eq!(pos_contract.urefs_lookup().len(), 2); // 1 for bonded validator, 1 for PoS purse.

        let validator_name: String = {
            let public_key_hex: String = addr_to_hex(&genesis_validator_public_key.value());
            // This is how PoS contract stores validator keys in its known_urefs map.
            format!("v_{}_{}", public_key_hex, genesis_validator_stake)
        };

        let bonded_validator = pos_contract.urefs_lookup().contains_key(&validator_name);

        assert!(bonded_validator);

        assert_eq!(
            pos_contract.urefs_lookup().get(POS_PURSE),
            Some(&Key::URef(pos_purse))
        );
    }

}
