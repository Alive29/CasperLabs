#![no_std]
#![no_main]

extern crate alloc;

use alloc::string::ToString;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key, URef};

#[no_mangle]
pub extern "C" fn call() {
    let contract_key: Key = runtime::get_key("hello_ext").unwrap_or_revert_with(ApiError::GetKey);

    let result: URef = runtime::call_contract(contract_key, ());

    let value = storage::read(result);

    assert_eq!(Ok(Some("Hello, world!".to_string())), value);
}
